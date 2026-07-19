package io.github.hhagenbuch.agentoperator.model;

/** A reference to a key within a Kubernetes Secret (e.g. the Anthropic API key). */
public class SecretKeyRef {
    public String name;
    public String key;
}
