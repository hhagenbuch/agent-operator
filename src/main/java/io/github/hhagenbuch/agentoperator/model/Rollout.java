package io.github.hhagenbuch.agentoperator.model;

/** How a PromptVersion rolls out. */
public class Rollout {
    /** {@code EvalGatedCanary} (default) or {@code Immediate} (dev only). */
    public String strategy = "EvalGatedCanary";
    /** Percent of traffic during canary — parsed but eval-only in the MVP (no traffic split). */
    public int canaryWeight = 0;
}
