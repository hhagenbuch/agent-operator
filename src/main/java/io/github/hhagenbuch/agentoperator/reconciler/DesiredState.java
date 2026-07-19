package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.github.hhagenbuch.agentoperator.model.Agent;
import io.github.hhagenbuch.agentoperator.model.AgentSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Pure functions that render an {@link Agent} into its owned Kubernetes objects:
 * a ConfigMap for the prompt, a Deployment, and a Service. No cluster access, so
 * this is exhaustively unit-testable — the reconciler just applies what these
 * return.
 *
 * <p>Key decision: the pod template carries a {@code prompt-hash} annotation over
 * the rendered prompt. Changing the prompt changes the hash, which changes the
 * pod template, which makes promotion an ordinary rolling update — we inherit
 * k8s rollout/rollback for free instead of reinventing it.
 */
public final class DesiredState {

    public static final String GROUP = "agents.hhagenbuch.io";
    public static final String PROMPT_HASH_ANNOTATION = GROUP + "/prompt-hash";
    public static final String PROMPT_KEY = "system-prompt";
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    private static final int PORT = 8080;

    private DesiredState() {
    }

    public static String configMapName(Agent agent) {
        return agent.getMetadata().getName() + "-" + agent.getSpec().activePromptVersion;
    }

    public static ConfigMap configMap(Agent agent) {
        AgentSpec spec = agent.getSpec();
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(configMapName(agent))
                .withNamespace(agent.getMetadata().getNamespace())
                .withLabels(labels(agent))
                .endMetadata()
                .withData(Map.of(PROMPT_KEY, spec.systemPrompt == null ? "" : spec.systemPrompt))
                .build();
    }

    public static Deployment deployment(Agent agent) {
        AgentSpec spec = agent.getSpec();
        String name = agent.getMetadata().getName();
        Map<String, String> selector = Map.of("app", name);

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(agent.getMetadata().getNamespace())
                .withLabels(labels(agent))
                .endMetadata()
                .withNewSpec()
                .withReplicas(spec.replicas)
                .withNewSelector().withMatchLabels(selector).endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels(agent))
                // Prompt change → hash change → new pod template → rolling update.
                .withAnnotations(Map.of(PROMPT_HASH_ANNOTATION, promptHash(spec.systemPrompt)))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("agent")
                .withImage(spec.image)
                .addNewPort().withContainerPort(PORT).endPort()
                .addNewEnv().withName("AGENT_MODEL").withValue(spec.model).endEnv()
                .addNewEnv()
                .withName("SYSTEM_PROMPT")
                .withNewValueFrom().withNewConfigMapKeyRef()
                .withName(configMapName(agent)).withKey(PROMPT_KEY)
                .endConfigMapKeyRef().endValueFrom()
                .endEnv()
                .addNewEnv()
                .withName("ANTHROPIC_API_KEY")
                .withNewValueFrom().withNewSecretKeyRef()
                .withName(spec.apiKeySecretRef.name).withKey(spec.apiKeySecretRef.key)
                .endSecretKeyRef().endValueFrom()
                .endEnv()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    public static Service service(Agent agent) {
        String name = agent.getMetadata().getName();
        return new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(agent.getMetadata().getNamespace())
                .withLabels(labels(agent))
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", name))
                .addNewPort().withPort(PORT).withNewTargetPort(PORT).endPort()
                .endSpec()
                .build();
    }

    /** Short, stable hex digest of the prompt content used as the roll trigger. */
    public static String promptHash(String prompt) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((prompt == null ? "" : prompt).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, String> labels(Agent agent) {
        return Map.of("app", agent.getMetadata().getName(), MANAGED_BY, "agent-operator");
    }
}
