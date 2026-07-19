package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.hhagenbuch.agentoperator.model.Agent;
import io.github.hhagenbuch.agentoperator.model.PromptVersion;
import io.github.hhagenbuch.agentoperator.model.PromptVersionStatus;
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
    private static final String EVALS_IMAGE = "ghcr.io/hhagenbuch/agent-evals:0.1.0";
    private static final Duration REQUEUE = Duration.ofSeconds(5);

    @Override
    public UpdateControl<PromptVersion> reconcile(PromptVersion pv, Context<PromptVersion> context) {
        KubernetesClient client = context.getClient();
        String ns = pv.getMetadata().getNamespace();

        Phase current = Phase.fromStatus(status(pv).phase);
        Job job = client.batch().v1().jobs().inNamespace(ns).withName(CanaryResources.evalJobName(pv)).get();
        JobOutcome outcome = outcomeOf(job);
        Decision decision = PromotionStateMachine.decide(current, outcome);

        Agent agent = client.resources(Agent.class).inNamespace(ns).withName(pv.getSpec().agentRef).get();
        if (agent == null && needsAgent(decision)) {
            status(pv).message = "waiting: Agent '" + pv.getSpec().agentRef + "' not found";
            return UpdateControl.patchStatus(pv).rescheduleAfter(REQUEUE);
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
                client.resource(CanaryResources.evalJob(pv, ns, EVALS_IMAGE,
                        agent.getSpec().evalGate.datasetConfigMap,
                        minPassRate(agent), agent.getSpec().apiKeySecretRef)).serverSideApply();
                log.info("PromptVersion '{}': eval Job launched", pv.getMetadata().getName());
                EventRecorder.record(client, pv, EventRecorder.NORMAL, "EvalStarted",
                        "Eval Job launched against the canary at min-pass-rate " + minPassRate(agent));
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
                status(pv).evalPassRate = "fail";
                status(pv).message = "rolled back: eval gate failed. " + reportTail(client, ns, pv);
                cleanupCanary(client, ns, pv);
                log.info("PromptVersion '{}': ROLLED BACK", pv.getMetadata().getName());
                EventRecorder.record(client, pv, EventRecorder.WARNING, "RolledBack",
                        "Eval gate failed; main Deployment left untouched. " + status(pv).message);
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

    private String minPassRate(Agent agent) {
        return agent.getSpec().evalGate != null && agent.getSpec().evalGate.minPassRate != null
                ? agent.getSpec().evalGate.minPassRate
                : "1.0";
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
