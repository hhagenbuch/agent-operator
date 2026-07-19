package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.github.hhagenbuch.agentoperator.model.PromptVersion;
import io.github.hhagenbuch.agentoperator.model.PromptVersionSpec;
import io.github.hhagenbuch.agentoperator.model.SecretKeyRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CanaryResourcesTest {

    private final SecretKeyRef secret = secret();

    private static SecretKeyRef secret() {
        SecretKeyRef ref = new SecretKeyRef();
        ref.name = "anthropic-key";
        ref.key = "api-key";
        return ref;
    }

    private PromptVersion promptVersion(String name, String prompt) {
        PromptVersion pv = new PromptVersion();
        pv.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("agents").build());
        PromptVersionSpec spec = new PromptVersionSpec();
        spec.agentRef = "support-agent";
        spec.systemPrompt = prompt;
        pv.setSpec(spec);
        return pv;
    }

    @Test
    void canaryNamesAreDerivedFromThePromptVersion() {
        PromptVersion pv = promptVersion("support-v4", "p");
        assertThat(CanaryResources.canaryName(pv)).isEqualTo("support-v4-canary");
        assertThat(CanaryResources.evalJobName(pv)).isEqualTo("support-v4-eval");
    }

    @Test
    void canaryDeploymentIsOneReplicaWithTheNewPromptAndAgentImage() {
        Deployment dep = CanaryResources.canaryDeployment(
                promptVersion("support-v4", "You answer only in French."),
                "agents", "img:1", "claude-sonnet-5", secret);

        assertThat(dep.getMetadata().getName()).isEqualTo("support-v4-canary");
        assertThat(dep.getSpec().getReplicas()).isEqualTo(1);
        assertThat(dep.getSpec().getTemplate().getMetadata().getLabels())
                .containsEntry(CanaryResources.ROLE_LABEL, "canary");
        var container = dep.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(container.getImage()).isEqualTo("img:1");
        EnvVar prompt = container.getEnv().stream()
                .filter(e -> e.getName().equals("SYSTEM_PROMPT")).findFirst().orElseThrow();
        assertThat(prompt.getValueFrom().getConfigMapKeyRef().getName()).isEqualTo("support-v4-canary-prompt");
    }

    @Test
    void canaryServiceSelectsTheCanaryPods() {
        var svc = CanaryResources.canaryService(promptVersion("support-v4", "p"), "agents");
        assertThat(svc.getSpec().getSelector()).containsEntry("app", "support-v4-canary");
        assertThat(svc.getSpec().getPorts().get(0).getPort()).isEqualTo(8080);
    }

    @Test
    void evalJobTargetsTheCanaryWithTheGateThreshold() {
        Job job = CanaryResources.evalJob(promptVersion("support-v4", "p"), "agents",
                "evals:1", "support-golden-cases", "0.9", secret);

        assertThat(job.getSpec().getBackoffLimit()).isZero(); // no retries — one clean pass/fail
        assertThat(job.getSpec().getActiveDeadlineSeconds())
                .isEqualTo(CanaryResources.EVAL_DEADLINE_SECONDS); // a stuck eval can't run forever
        var container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertThat(container.getImage()).isEqualTo("evals:1");
        assertThat(container.getArgs()).containsSubsequence(
                "--target", "http://support-v4-canary:8080/api/chat", "--min-pass-rate", "0.9");
        // API key threaded into the eval Job so judge assertions can run
        EnvVar key = container.getEnv().stream()
                .filter(e -> e.getName().equals("ANTHROPIC_API_KEY")).findFirst().orElseThrow();
        assertThat(key.getValueFrom().getSecretKeyRef().getName()).isEqualTo("anthropic-key");
        // dataset mounted from the ConfigMap named by the gate
        assertThat(job.getSpec().getTemplate().getSpec().getVolumes().get(0)
                .getConfigMap().getName()).isEqualTo("support-golden-cases");
    }
}
