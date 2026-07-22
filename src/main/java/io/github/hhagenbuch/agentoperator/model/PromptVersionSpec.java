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
    /**
     * When true, a passed eval gate holds at {@code AwaitingApproval} instead of
     * promoting; a human (or a controller acting for one) releases it with the
     * {@code agents.hhagenbuch.io/approved} annotation: {@code "true"} promotes,
     * {@code "false"} rolls back. Null/false keeps the auto-promote behavior.
     */
    public Boolean requireApproval;
    /**
     * Optional per-version gate override. Any field set here (dataset, min pass
     * rate, image) wins over the Agent's {@code evalGate} for THIS version only —
     * e.g. gating one rollout against a suite extended with a new regression case.
     */
    public EvalGate evalGateOverride;
}
