package io.github.hhagenbuch.agentoperator.model;

/**
 * SLO policy for the continuous-eval-pass-rate SLI (the agent-slo RFC's proving
 * slice). When set, the operator computes the windowed pass rate from the
 * samples ConfigMap and freezes prompt promotions when the error budget is
 * exhausted. See {@code SloPolicyCheck} for the arithmetic.
 */
public class SloPolicy {
    /** SLO target as a string ratio, e.g. "0.95" (mirrors {@link EvalGate#minPassRate}). */
    public String target;
    /** Rolling window, {@code 7d}/{@code 12h}/{@code 5m}/{@code 30s} style. */
    public String window = "7d";
    /** Minimum valid case executions in the window before the SLI is actionable. */
    public Integer minSamples;
    /** ConfigMap holding {@code samples.jsonl}; defaults to {@code <agent>-slo-samples}. */
    public String samplesConfigMap;
}
