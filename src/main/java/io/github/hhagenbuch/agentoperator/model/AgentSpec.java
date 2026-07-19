package io.github.hhagenbuch.agentoperator.model;

/**
 * Desired state of an agent workload. {@code activePromptVersion} is managed by
 * the operator (Phase 2's PromptVersion controller patches it on promotion); in
 * Phase 1 the active prompt content is carried inline as {@code systemPrompt}.
 */
public class AgentSpec {
    /** Container image of the agent service (e.g. the spring-ai-agent-starter image). */
    public String image;
    /** Desired replica count for the main Deployment. */
    public int replicas = 1;
    /** Model id the agent runs (e.g. claude-sonnet-5). */
    public String model;
    /** Where the agent's API key lives. */
    public SecretKeyRef apiKeySecretRef;
    /** The promotion-managed active prompt version name; names the rendered ConfigMap. */
    public String activePromptVersion;
    /** Phase-1 inline prompt content for the active version (superseded by PromptVersion in Phase 2). */
    public String systemPrompt;
    /** The eval gate promotions must pass. */
    public EvalGate evalGate;
}
