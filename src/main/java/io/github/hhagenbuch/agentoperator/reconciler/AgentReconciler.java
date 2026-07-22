package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.hhagenbuch.agentoperator.model.Agent;
import io.github.hhagenbuch.agentoperator.model.AgentStatus;
import io.github.hhagenbuch.agentoperator.model.SloPolicy;
import io.github.hhagenbuch.agentoperator.reconciler.SloPolicyCheck.SloVerdict;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Reconciles an {@link Agent} to a Deployment + Service + ConfigMap that match
 * its spec. All desired objects come from {@link DesiredState}; this class only
 * applies them and records the observed prompt version in status. When the spec
 * carries an {@link SloPolicy}, each reconcile also re-evaluates the SLO error
 * budget ({@link SloPolicyCheck}) and maintains {@code status.promotionsFrozen}.
 */
@ControllerConfiguration
public class AgentReconciler implements Reconciler<Agent> {

    private static final Logger log = LoggerFactory.getLogger(AgentReconciler.class);
    /** Data key in the samples ConfigMap the scheduled eval runner appends to. */
    public static final String SAMPLES_KEY = "samples.jsonl";
    /** Applied when {@code sloPolicy.minSamples} is unset (RFC §3.3 minimum-evidence rule). */
    static final int DEFAULT_MIN_SAMPLES = 50;
    /** Re-evaluate cadence while an SLO policy is set: the samples ConfigMap is
     *  written by the runner, not the operator, so nothing else triggers us. */
    private static final Duration SLO_RECHECK = Duration.ofSeconds(30);

    @Override
    public UpdateControl<Agent> reconcile(Agent agent, Context<Agent> context) {
        KubernetesClient client = context.getClient();

        client.resource(DesiredState.configMap(agent)).serverSideApply();
        client.resource(DesiredState.deployment(agent)).serverSideApply();
        client.resource(DesiredState.service(agent)).serverSideApply();

        log.info("Reconciled agent '{}' at prompt version '{}'",
                agent.getMetadata().getName(), agent.getSpec().activePromptVersion);

        if (agent.getStatus() == null) {
            agent.setStatus(new AgentStatus());
        }
        agent.getStatus().observedPromptVersion = agent.getSpec().activePromptVersion;

        if (agent.getSpec().sloPolicy == null) {
            return UpdateControl.patchStatus(agent);
        }
        evaluateSlo(agent, client);
        return UpdateControl.patchStatus(agent).rescheduleAfter(SLO_RECHECK);
    }

    private void evaluateSlo(Agent agent, KubernetesClient client) {
        SloPolicy slo = agent.getSpec().sloPolicy;
        String ns = agent.getMetadata().getNamespace();
        String cmName = slo.samplesConfigMap != null
                ? slo.samplesConfigMap
                : agent.getMetadata().getName() + "-slo-samples";
        ConfigMap cm = client.configMaps().inNamespace(ns).withName(cmName).get();
        String jsonl = cm == null || cm.getData() == null ? null : cm.getData().get(SAMPLES_KEY);

        boolean currentlyFrozen = Boolean.TRUE.equals(agent.getStatus().promotionsFrozen);
        SloVerdict verdict;
        try {
            verdict = SloPolicyCheck.evaluate(
                    SloPolicyCheck.parseSamples(jsonl),
                    Instant.now(),
                    SloPolicyCheck.parseWindow(slo.window != null ? slo.window : "7d"),
                    Double.parseDouble(slo.target),
                    slo.minSamples != null ? slo.minSamples : DEFAULT_MIN_SAMPLES,
                    currentlyFrozen);
        } catch (RuntimeException e) {
            // A broken policy must be visible, and must not change the freeze state.
            agent.getStatus().sloMessage = "sloPolicy invalid: " + e.getMessage();
            log.warn("Agent '{}': sloPolicy invalid: {}", agent.getMetadata().getName(), e.getMessage());
            return;
        }

        agent.getStatus().promotionsFrozen = verdict.frozen();
        agent.getStatus().sloMessage = verdict.message();
        if (verdict.frozen() != currentlyFrozen) {
            if (verdict.frozen()) {
                log.warn("Agent '{}': PROMOTIONS FROZEN — {}", agent.getMetadata().getName(), verdict.message());
                EventRecorder.record(client, agent, EventRecorder.WARNING, "PromotionsFrozen",
                        verdict.message() + " New PromptVersions will be refused unless annotated "
                                + PromptVersionReconciler.SLO_EXEMPT_ANNOTATION + "=" + SloPolicyCheck.EXEMPT_VALUE);
            } else {
                log.info("Agent '{}': promotions unfrozen — {}", agent.getMetadata().getName(), verdict.message());
                EventRecorder.record(client, agent, EventRecorder.NORMAL, "PromotionsUnfrozen", verdict.message());
            }
        }
    }
}
