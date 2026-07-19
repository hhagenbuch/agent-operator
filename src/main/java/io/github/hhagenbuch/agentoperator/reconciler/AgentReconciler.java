package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.hhagenbuch.agentoperator.model.Agent;
import io.github.hhagenbuch.agentoperator.model.AgentStatus;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles an {@link Agent} to a Deployment + Service + ConfigMap that match
 * its spec. All desired objects come from {@link DesiredState}; this class only
 * applies them and records the observed prompt version in status.
 */
@ControllerConfiguration
public class AgentReconciler implements Reconciler<Agent> {

    private static final Logger log = LoggerFactory.getLogger(AgentReconciler.class);

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
        return UpdateControl.patchStatus(agent);
    }
}
