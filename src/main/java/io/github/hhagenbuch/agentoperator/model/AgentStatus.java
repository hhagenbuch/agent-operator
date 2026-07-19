package io.github.hhagenbuch.agentoperator.model;

/** Observed state the operator writes back. */
public class AgentStatus {
    /** The prompt version currently rolled out (mirrors spec once reconciled). */
    public String observedPromptVersion;
}
