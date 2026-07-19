package io.github.hhagenbuch.agentoperator.model;

/**
 * The eval gate a new prompt/model must pass before promotion. Consumed by the
 * PromptVersion controller in Phase 2; carried on the Agent so the gate travels
 * with the workload.
 */
public class EvalGate {
    /** ConfigMap holding the agent-evals dataset YAML. */
    public String datasetConfigMap;
    /** Minimum pass rate, as a string (e.g. "0.9") to match kubectl/CRD conventions. */
    public String minPassRate;
    /** Image running the agent-evals jar; defaults to {@code ghcr.io/hhagenbuch/agent-evals:0.1.0}. */
    public String image;
}
