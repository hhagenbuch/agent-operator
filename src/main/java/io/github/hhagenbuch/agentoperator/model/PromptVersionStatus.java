package io.github.hhagenbuch.agentoperator.model;

import io.fabric8.crd.generator.annotation.PrinterColumn;

/**
 * Where the product lives: {@code kubectl get promptversions} shows the phase
 * and pass rate, and {@code message} carries the eval report summary — so a
 * rollback tells you <em>why</em>.
 */
public class PromptVersionStatus {
    /** Pending → Canary → Evaluating → Promoted | RolledBack. */
    @PrinterColumn(name = "PHASE")
    public String phase;

    /** Observed eval pass rate (e.g. "0.94"), set once the gate has run. */
    @PrinterColumn(name = "PASSRATE")
    public String evalPassRate;

    /** Human-readable detail — on a rollback, the tail of the eval report. */
    public String message;

    /** ISO-8601 instant the eval Job was launched; used to enforce the gate timeout. */
    public String evalStartedAt;
}
