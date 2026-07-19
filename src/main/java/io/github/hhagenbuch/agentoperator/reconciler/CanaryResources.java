package io.github.hhagenbuch.agentoperator.reconciler;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.github.hhagenbuch.agentoperator.model.PromptVersion;
import io.github.hhagenbuch.agentoperator.model.SecretKeyRef;

import java.util.Map;

/**
 * Pure builders for the eval-gated canary a PromptVersion spins up: a 1-replica
 * canary Deployment (with the new prompt, NOT wired to the main Service), a
 * canary Service the eval Job targets, and the eval Job itself — the
 * {@code agent-evals} jar run against the canary with the gate's threshold.
 */
public final class CanaryResources {

    static final String ROLE_LABEL = DesiredState.GROUP + "/role";
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    private static final int PORT = 8080;
    private static final String DATASET_MOUNT = "/data";
    private static final String DATASET_FILE = "dataset.yaml";

    private CanaryResources() {
    }

    public static String canaryName(PromptVersion pv) {
        return pv.getMetadata().getName() + "-canary";
    }

    public static String evalJobName(PromptVersion pv) {
        return pv.getMetadata().getName() + "-eval";
    }

    public static ConfigMap canaryConfigMap(PromptVersion pv, String namespace) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(canaryName(pv) + "-prompt")
                .withNamespace(namespace)
                .withLabels(labels(pv))
                .endMetadata()
                .withData(Map.of(DesiredState.PROMPT_KEY,
                        pv.getSpec().systemPrompt == null ? "" : pv.getSpec().systemPrompt))
                .build();
    }

    public static Deployment canaryDeployment(PromptVersion pv, String namespace,
                                              String image, String model, SecretKeyRef apiKeySecretRef) {
        String name = canaryName(pv);
        Map<String, String> selector = Map.of("app", name);
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name).withNamespace(namespace).withLabels(labels(pv))
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().withMatchLabels(selector).endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels(pv))
                .withAnnotations(Map.of(DesiredState.PROMPT_HASH_ANNOTATION,
                        DesiredState.promptHash(pv.getSpec().systemPrompt)))
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("agent")
                .withImage(image)
                .addNewPort().withContainerPort(PORT).endPort()
                .addNewEnv().withName("AGENT_MODEL").withValue(model).endEnv()
                .addNewEnv()
                .withName("SYSTEM_PROMPT")
                .withNewValueFrom().withNewConfigMapKeyRef()
                .withName(canaryName(pv) + "-prompt").withKey(DesiredState.PROMPT_KEY)
                .endConfigMapKeyRef().endValueFrom()
                .endEnv()
                .addNewEnv()
                .withName("ANTHROPIC_API_KEY")
                .withNewValueFrom().withNewSecretKeyRef()
                .withName(apiKeySecretRef.name).withKey(apiKeySecretRef.key)
                .endSecretKeyRef().endValueFrom()
                .endEnv()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    public static Service canaryService(PromptVersion pv, String namespace) {
        String name = canaryName(pv);
        return new ServiceBuilder()
                .withNewMetadata()
                .withName(name).withNamespace(namespace).withLabels(labels(pv))
                .endMetadata()
                .withNewSpec()
                .withSelector(Map.of("app", name))
                .addNewPort().withPort(PORT).withNewTargetPort(PORT).endPort()
                .endSpec()
                .build();
    }

    public static Job evalJob(PromptVersion pv, String namespace, String evalsImage,
                              String datasetConfigMap, String minPassRate, SecretKeyRef apiKeySecretRef) {
        String target = "http://" + canaryName(pv) + ":" + PORT + "/api/chat";
        return new JobBuilder()
                .withNewMetadata()
                .withName(evalJobName(pv)).withNamespace(namespace).withLabels(labels(pv))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withNewTemplate()
                .withNewMetadata().withLabels(labels(pv)).endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("eval")
                .withImage(evalsImage)
                .withArgs(DATASET_MOUNT + "/" + DATASET_FILE,
                        "--target", target,
                        "--min-pass-rate", minPassRate)
                .addNewEnv()
                .withName("ANTHROPIC_API_KEY")
                .withNewValueFrom().withNewSecretKeyRef()
                .withName(apiKeySecretRef.name).withKey(apiKeySecretRef.key)
                .endSecretKeyRef().endValueFrom()
                .endEnv()
                .addNewVolumeMount().withName("dataset").withMountPath(DATASET_MOUNT).endVolumeMount()
                .endContainer()
                .addNewVolume()
                .withName("dataset")
                .withNewConfigMap().withName(datasetConfigMap).endConfigMap()
                .endVolume()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static Map<String, String> labels(PromptVersion pv) {
        return Map.of(
                "app", canaryName(pv),
                ROLE_LABEL, "canary",
                MANAGED_BY, "agent-operator");
    }
}
