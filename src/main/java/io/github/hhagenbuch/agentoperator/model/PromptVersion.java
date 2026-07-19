package io.github.hhagenbuch.agentoperator.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The {@code PromptVersion} custom resource — an eval-gated rollout of a new
 * prompt/model to an {@link Agent}. Applying one spins up a canary, runs an eval
 * Job against it, and promotes or rolls back on the result.
 */
@Group("agents.hhagenbuch.io")
@Version("v1alpha1")
@ShortNames("pv")
public class PromptVersion extends CustomResource<PromptVersionSpec, PromptVersionStatus> implements Namespaced {
}
