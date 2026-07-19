package io.github.hhagenbuch.agentoperator.model;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The {@code Agent} custom resource — a long-running agent workload. The CRD YAML
 * is generated from this class at compile time by {@code crd-generator-apt}
 * (single source of truth), landing under {@code target/classes/META-INF/fabric8}.
 */
@Group("agents.hhagenbuch.io")
@Version("v1alpha1")
@ShortNames("ag")
public class Agent extends CustomResource<AgentSpec, AgentStatus> implements Namespaced {
}
