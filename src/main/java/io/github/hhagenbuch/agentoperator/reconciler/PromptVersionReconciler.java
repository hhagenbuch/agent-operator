package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.hhagenbuch.agentoperator.model.Agent;
import io.github.hhagenbuch.agentoperator.model.EvalGate;
import io.github.hhagenbuch.agentoperator.model.PromptVersion;
import io.github.hhagenbuch.agentoperator.model.PromptVersionStatus;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Approval;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Decision;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.JobOutcome;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Phase;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Drives a {@link PromptVersion} through the {@link PromotionStateMachine}:
 * spin up a canary, run the eval Job, then promote (patch the Agent's active
 * prompt) or roll back (leaving the main Deployment untouched and recording why).
 * The pure decision lives in {@link PromotionStateMachine}; this class only
 * performs the cluster effects the decision calls for.
 */
@ControllerConfiguration
public class PromptVersionReconciler implements Reconciler<PromptVersion> {

    private static final Logger log = LoggerFactory.getLogger(PromptVersionReconciler.class);
    // 0.2.1 carries the runner's cold-start/transient-connection retry: a canary
    // that is still warming up no longer fails the gate on a dropped connection.
    private static final String DEFAULT_EVALS_IMAGE = "ghcr.io/hhagenbuch/agent-evals:0.2.1";
    /** Set to "true" to release an {@code AwaitingApproval} hold, "false" to reject it. */
    public static final String APPROVED_ANNOTATION = "agents.hhagenbuch.io/approved";
    /** Set to "fix" to assert this PromptVersion restores the SLO: it passes a
     *  promotion freeze (never the eval gate). */
    public static final String SLO_EXEMPT_ANNOTATION = "agents.hhagenbuch.io/slo-exempt";
    /** Cosmetic status.phase while refused by a freeze; not a {@link Phase} — it maps
     *  back to {@code PENDING} so the promotion starts normally once the freeze lifts. */
    public static final String FROZEN_PHASE = "Frozen";
    private static final Duration REQUEUE = Duration.ofSeconds(5);
    private static final Duration FROZEN_RECHECK = Duration.ofSeconds(15);
    private static final Duration GATE_TIMEOUT = Duration.ofSeconds(CanaryResources.EVAL_DEADLINE_SECONDS);

    @Override
    public UpdateControl<PromptVersion> reconcile(PromptVersion pv, Context<PromptVersion> context) {
        KubernetesClient client = context.getClient();
        String ns = pv.getMetadata().getNamespace();

        Phase current = Phase.fromStatus(status(pv).phase);
        Job job = client.batch().v1().jobs().inNamespace(ns).withName(CanaryResources.evalJobName(pv)).get();
        JobOutcome outcome = outcomeOf(job);
        boolean deadlineExceeded = gateDeadlineExceeded(pv);
        Decision decision = PromotionStateMachine.decide(current, outcome, deadlineExceeded,
                Boolean.TRUE.equals(pv.getSpec().requireApproval), approvalOf(pv));

        Agent agent = client.resources(Agent.class).inNamespace(ns).withName(pv.getSpec().agentRef).get();
        if (agent == null && needsAgent(decision)) {
            status(pv).message = "waiting: Agent '" + pv.getSpec().agentRef + "' not found";
            return UpdateControl.patchStatus(pv).rescheduleAfter(REQUEUE);
        }

        // SLO freeze gate: a promotion that has not started yet is refused while the
        // Agent's error budget is exhausted. In-flight promotions are never interrupted.
        if (current == Phase.PENDING && agent != null
                && SloPolicyCheck.refusesPromotion(
                        agent.getStatus() == null ? null : agent.getStatus().promotionsFrozen,
                        exemptOf(pv))) {
            boolean firstRefusal = !FROZEN_PHASE.equals(status(pv).phase);
            status(pv).phase = FROZEN_PHASE;
            status(pv).message = "refused: Agent '" + pv.getSpec().agentRef
                    + "' promotions are frozen (SLO error budget exhausted). "
                    + (agent.getStatus().sloMessage != null ? agent.getStatus().sloMessage + " " : "")
                    + "Annotate " + SLO_EXEMPT_ANNOTATION + "=" + SloPolicyCheck.EXEMPT_VALUE
                    + " only if this change restores the SLO; it will still run the eval gate.";
            if (firstRefusal) {
                log.warn("PromptVersion '{}': REFUSED — Agent '{}' is frozen",
                        pv.getMetadata().getName(), pv.getSpec().agentRef);
                EventRecorder.record(client, pv, EventRecorder.WARNING, "PromotionFrozen",
                        status(pv).message);
            }
            return UpdateControl.patchStatus(pv).rescheduleAfter(FROZEN_RECHECK);
        }

        switch (decision.action()) {
            case CREATE_CANARY -> {
                client.resource(CanaryResources.canaryConfigMap(pv, ns)).serverSideApply();
                client.resource(CanaryResources.canaryDeployment(pv, ns,
                        agent.getSpec().image, resolveModel(pv, agent), agent.getSpec().apiKeySecretRef))
                        .serverSideApply();
                client.resource(CanaryResources.canaryService(pv, ns)).serverSideApply();
                log.info("PromptVersion '{}': canary created", pv.getMetadata().getName());
                EventRecorder.record(client, pv, EventRecorder.NORMAL, "CanaryCreated",
                        "Canary Deployment/Service/ConfigMap created (off the main Service)");
            }
            case CREATE_JOB -> {
                EvalGate gate = effectiveGate(pv, agent);
                client.resource(CanaryResources.evalJob(pv, ns, gate.image,
                        gate.datasetConfigMap, gate.minPassRate, agent.getSpec().apiKeySecretRef))
                        .serverSideApply();
                status(pv).evalStartedAt = Instant.now().toString();
                log.info("PromptVersion '{}': eval Job launched", pv.getMetadata().getName());
                EventRecorder.record(client, pv, EventRecorder.NORMAL, "EvalStarted",
                        "Eval Job launched against the canary at min-pass-rate " + gate.minPassRate
                                + (pv.getSpec().evalGateOverride != null ? " (per-version gate override)" : ""));
            }
            case HOLD -> {
                status(pv).evalPassRate = "pass";
                status(pv).message = "eval gate passed — awaiting approval: annotate this PromptVersion "
                        + APPROVED_ANNOTATION + "=true to promote, =false to roll back";
                log.info("PromptVersion '{}': gate passed, AWAITING APPROVAL", pv.getMetadata().getName());
                EventRecorder.record(client, pv, EventRecorder.NORMAL, "AwaitingApproval",
                        "Eval gate passed; promotion held for approval (" + APPROVED_ANNOTATION + ")");
            }
            case PROMOTE -> {
                agent.getSpec().activePromptVersion = pv.getMetadata().getName();
                agent.getSpec().systemPrompt = pv.getSpec().systemPrompt;
                if (pv.getSpec().model != null) {
                    agent.getSpec().model = pv.getSpec().model;
                }
                client.resource(agent).update();
                cleanupCanary(client, ns, pv);
                status(pv).evalPassRate = "pass";
                status(pv).message = "promoted: eval gate passed; Agent now serves this prompt";
                log.info("PromptVersion '{}': PROMOTED", pv.getMetadata().getName());
                EventRecorder.record(client, pv, EventRecorder.NORMAL, "Promoted",
                        "Eval gate passed; Agent '" + pv.getSpec().agentRef + "' now serves this prompt");
            }
            case ROLLBACK -> {
                boolean rejected = current == Phase.AWAITING_APPROVAL;
                boolean timedOut = !rejected && outcome != JobOutcome.FAILED; // via the deadline, not a fail
                status(pv).evalPassRate = rejected ? "pass" : "fail";
                status(pv).message = rejected
                        ? "rolled back: approval rejected (" + APPROVED_ANNOTATION + "=false)"
                        : timedOut
                        ? "rolled back: eval gate timed out after " + GATE_TIMEOUT.toSeconds() + "s"
                        : "rolled back: eval gate failed. " + reportTail(client, ns, pv);
                cleanupCanary(client, ns, pv);
                log.info("PromptVersion '{}': ROLLED BACK ({})", pv.getMetadata().getName(),
                        rejected ? "approval rejected" : timedOut ? "timeout" : "eval failure");
                EventRecorder.record(client, pv, EventRecorder.WARNING, "RolledBack",
                        "Main Deployment left untouched. " + status(pv).message);
            }
            case WAIT -> { /* eval Job still running — requeue */ }
            case DONE -> { /* terminal */ }
        }

        status(pv).phase = decision.nextPhase().value();
        return terminal(decision.nextPhase())
                ? UpdateControl.patchStatus(pv)
                : UpdateControl.patchStatus(pv).rescheduleAfter(REQUEUE);
    }

    private static boolean needsAgent(Decision decision) {
        return switch (decision.action()) {
            case CREATE_CANARY, CREATE_JOB, PROMOTE -> true;
            default -> false;
        };
    }

    private static boolean terminal(Phase phase) {
        return phase == Phase.PROMOTED || phase == Phase.ROLLED_BACK;
    }

    private static String exemptOf(PromptVersion pv) {
        var annotations = pv.getMetadata().getAnnotations();
        return annotations == null ? null : annotations.get(SLO_EXEMPT_ANNOTATION);
    }

    private static Approval approvalOf(PromptVersion pv) {
        var annotations = pv.getMetadata().getAnnotations();
        String value = annotations == null ? null : annotations.get(APPROVED_ANNOTATION);
        if ("true".equalsIgnoreCase(value)) {
            return Approval.APPROVED;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Approval.REJECTED;
        }
        return Approval.NONE;
    }

    /** The Agent's gate with any per-version override fields applied on top. */
    private EvalGate effectiveGate(PromptVersion pv, Agent agent) {
        EvalGate base = agent.getSpec().evalGate != null ? agent.getSpec().evalGate : new EvalGate();
        EvalGate override = pv.getSpec().evalGateOverride;
        EvalGate gate = new EvalGate();
        gate.datasetConfigMap = firstNonNull(override == null ? null : override.datasetConfigMap,
                base.datasetConfigMap);
        gate.minPassRate = firstNonNull(override == null ? null : override.minPassRate,
                base.minPassRate, "1.0");
        gate.image = firstNonNull(override == null ? null : override.image, base.image, DEFAULT_EVALS_IMAGE);
        return gate;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private JobOutcome outcomeOf(Job job) {
        if (job == null || job.getStatus() == null) {
            return JobOutcome.NONE;
        }
        Integer succeeded = job.getStatus().getSucceeded();
        Integer failed = job.getStatus().getFailed();
        if (succeeded != null && succeeded >= 1) {
            return JobOutcome.SUCCEEDED;
        }
        if (failed != null && failed >= 1) {
            return JobOutcome.FAILED;
        }
        return JobOutcome.RUNNING;
    }

    private String resolveModel(PromptVersion pv, Agent agent) {
        return pv.getSpec().model != null ? pv.getSpec().model : agent.getSpec().model;
    }

    /** True once the eval has been running longer than the gate budget, so a stuck Job still terminates. */
    private boolean gateDeadlineExceeded(PromptVersion pv) {
        String startedAt = status(pv).evalStartedAt;
        if (startedAt == null) {
            return false;
        }
        try {
            return Instant.parse(startedAt).plus(GATE_TIMEOUT).isBefore(Instant.now());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void cleanupCanary(KubernetesClient client, String ns, PromptVersion pv) {
        String canary = CanaryResources.canaryName(pv);
        client.apps().deployments().inNamespace(ns).withName(canary).delete();
        client.services().inNamespace(ns).withName(canary).delete();
        client.configMaps().inNamespace(ns).withName(canary + "-prompt").delete();
        client.batch().v1().jobs().inNamespace(ns).withName(CanaryResources.evalJobName(pv)).delete();
    }

    /** Best-effort tail of the eval Job log, so a rollback says why in kubectl describe. */
    private String reportTail(KubernetesClient client, String ns, PromptVersion pv) {
        try {
            String logs = client.batch().v1().jobs().inNamespace(ns)
                    .withName(CanaryResources.evalJobName(pv)).getLog();
            if (logs == null || logs.isBlank()) {
                return "(no eval output captured)";
            }
            String trimmed = logs.strip();
            return trimmed.length() > 500 ? "..." + trimmed.substring(trimmed.length() - 500) : trimmed;
        } catch (RuntimeException e) {
            return "(eval log unavailable: " + e.getMessage() + ")";
        }
    }

    private PromptVersionStatus status(PromptVersion pv) {
        if (pv.getStatus() == null) {
            pv.setStatus(new PromptVersionStatus());
        }
        return pv.getStatus();
    }
}
