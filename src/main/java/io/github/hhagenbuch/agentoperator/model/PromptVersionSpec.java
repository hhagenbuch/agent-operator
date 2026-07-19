package io.github.hhagenbuch.agentoperator.model;

/** An immutable, versioned prompt/model change to roll out to an {@link Agent}. */
public class PromptVersionSpec {
    /** Name of the Agent this version targets (same namespace). */
    public String agentRef;
    /** The system prompt for this version. */
    public String systemPrompt;
    /** Optional model override; model bumps flow through the same eval gate. */
    public String model;
    /** Rollout strategy. */
    public Rollout rollout = new Rollout();
}
