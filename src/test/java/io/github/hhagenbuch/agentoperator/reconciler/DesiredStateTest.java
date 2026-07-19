package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.github.hhagenbuch.agentoperator.model.Agent;
import io.github.hhagenbuch.agentoperator.model.AgentSpec;
import io.github.hhagenbuch.agentoperator.model.SecretKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DesiredStateTest {

    private Agent agent(String promptVersion, String prompt) {
        Agent agent = new Agent();
        agent.setMetadata(new ObjectMetaBuilder().withName("support-agent").withNamespace("agents").build());
        AgentSpec spec = new AgentSpec();
        spec.image = "ghcr.io/hhagenbuch/spring-ai-agent-starter:0.1.0";
        spec.replicas = 2;
        spec.model = "claude-sonnet-5";
        spec.activePromptVersion = promptVersion;
        spec.systemPrompt = prompt;
        spec.apiKeySecretRef = new SecretKeyRef();
        spec.apiKeySecretRef.name = "anthropic-key";
        spec.apiKeySecretRef.key = "api-key";
        agent.setSpec(spec);
        return agent;
    }

    private Container container(Deployment deployment) {
        return deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
    }

    private EnvVar env(Deployment deployment, String name) {
        return container(deployment).getEnv().stream()
                .filter(e -> e.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void configMapIsNamedAgentDashPromptVersionAndHoldsThePrompt() {
        Agent agent = agent("support-v3", "You are a support agent.");
        var cm = DesiredState.configMap(agent);
        assertThat(cm.getMetadata().getName()).isEqualTo("support-agent-support-v3");
        assertThat(cm.getMetadata().getNamespace()).isEqualTo("agents");
        assertThat(cm.getData()).containsEntry("system-prompt", "You are a support agent.");
    }

    @Test
    void deploymentCarriesSpecImageReplicasAndWiredEnv() {
        Deployment deployment = DesiredState.deployment(agent("support-v3", "You are a support agent."));
        assertThat(deployment.getSpec().getReplicas()).isEqualTo(2);
        assertThat(container(deployment).getImage())
                .isEqualTo("ghcr.io/hhagenbuch/spring-ai-agent-starter:0.1.0");
        assertThat(env(deployment, "AGENT_MODEL").getValue()).isEqualTo("claude-sonnet-5");
        // API key comes from the referenced secret, never inlined
        var secretRef = env(deployment, "ANTHROPIC_API_KEY").getValueFrom().getSecretKeyRef();
        assertThat(secretRef.getName()).isEqualTo("anthropic-key");
        assertThat(secretRef.getKey()).isEqualTo("api-key");
        // prompt is mounted from the versioned ConfigMap
        var cmRef = env(deployment, "SYSTEM_PROMPT").getValueFrom().getConfigMapKeyRef();
        assertThat(cmRef.getName()).isEqualTo("support-agent-support-v3");
        assertThat(cmRef.getKey()).isEqualTo("system-prompt");
    }

    @Test
    void podTemplateHashChangesWithThePromptSoAPromotionRolls() {
        Deployment a = DesiredState.deployment(agent("support-v3", "You are a support agent."));
        Deployment b = DesiredState.deployment(agent("support-v4", "You are a terse support agent."));

        String hashA = a.getSpec().getTemplate().getMetadata()
                .getAnnotations().get(DesiredState.PROMPT_HASH_ANNOTATION);
        String hashB = b.getSpec().getTemplate().getMetadata()
                .getAnnotations().get(DesiredState.PROMPT_HASH_ANNOTATION);

        assertThat(hashA).isNotBlank();
        assertThat(hashB).isNotEqualTo(hashA); // different prompt → different pod template → rolling update
    }

    @Test
    void serviceSelectsTheAgentPods() {
        var service = DesiredState.service(agent("support-v3", "p"));
        assertThat(service.getSpec().getSelector()).containsEntry("app", "support-agent");
        assertThat(service.getSpec().getPorts().get(0).getPort()).isEqualTo(8080);
    }

    @Test
    void promptHashIsStableForTheSameContent() {
        assertThat(DesiredState.promptHash("hello")).isEqualTo(DesiredState.promptHash("hello"));
        assertThat(DesiredState.promptHash("hello")).isNotEqualTo(DesiredState.promptHash("world"));
    }
}
