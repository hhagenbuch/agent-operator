package io.github.hhagenbuch.agentoperator.model;

import io.fabric8.crd.generator.annotation.PrinterColumn;

/** Observed state the operator writes back. */
public class AgentStatus {
    /** The prompt version currently rolled out (mirrors spec once reconciled). */
    public String observedPromptVersion;
    /** True while the SLO error budget is exhausted: new PromptVersions are refused
     *  unless annotated agents.hhagenbuch.io/slo-exempt=fix. */
    @PrinterColumn(name = "FROZEN")
    public Boolean promotionsFrozen;
    /** Human-readable SLO state (pass rate, budget consumed, freeze reason). */
    public String sloMessage;
}
