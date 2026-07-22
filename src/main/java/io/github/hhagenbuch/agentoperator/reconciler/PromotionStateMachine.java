package io.github.hhagenbuch.agentoperator.reconciler;

/**
 * The PromptVersion promotion state machine as a pure function:
 * {@code (currentPhase, evalJobOutcome, deadlineExceeded) → (action, nextPhase)}.
 * Keeping the decision separate from cluster I/O makes the whole promotion/
 * rollback policy exhaustively unit-testable — the reconciler just executes the
 * returned action. {@code deadlineExceeded} guarantees a terminal outcome even if
 * the eval Job never reports (deleted, wedged on image pull, canary never up).
 *
 * <pre>
 * Pending ──► Canary ──► Evaluating ──► Promoted
 *                            │              ▲
 *                            │   (requireApproval)
 *                            ├──► AwaitingApproval ──► RolledBack (rejected)
 *                            │
 *                            └────────► RolledBack
 * </pre>
 *
 * <p>With {@code requireApproval}, a passed gate holds at {@code AwaitingApproval}
 * until the {@code agents.hhagenbuch.io/approved} annotation arrives: {@code "true"}
 * promotes, {@code "false"} rolls back. The gate deadline is deliberately not
 * consulted while awaiting approval — humans are allowed to take their time.
 */
public final class PromotionStateMachine {

    public enum Phase {
        PENDING("Pending"),
        CANARY("Canary"),
        EVALUATING("Evaluating"),
        AWAITING_APPROVAL("AwaitingApproval"),
        PROMOTED("Promoted"),
        ROLLED_BACK("RolledBack");

        private final String value;

        Phase(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        public static Phase fromStatus(String status) {
            if (status != null) {
                for (Phase phase : values()) {
                    if (phase.value.equals(status)) {
                        return phase;
                    }
                }
            }
            return PENDING;
        }
    }

    /** Observed state of the eval Job. */
    public enum JobOutcome {
        NONE, RUNNING, SUCCEEDED, FAILED
    }

    /** Observed state of the {@code agents.hhagenbuch.io/approved} annotation. */
    public enum Approval {
        NONE, APPROVED, REJECTED
    }

    public enum Action {
        CREATE_CANARY, CREATE_JOB, WAIT, HOLD, PROMOTE, ROLLBACK, DONE
    }

    public record Decision(Action action, Phase nextPhase) {
    }

    private PromotionStateMachine() {
    }

    /** The auto-promote flow — no approval hold. */
    public static Decision decide(Phase current, JobOutcome outcome, boolean deadlineExceeded) {
        return decide(current, outcome, deadlineExceeded, false, Approval.NONE);
    }

    public static Decision decide(Phase current, JobOutcome outcome, boolean deadlineExceeded,
                                  boolean requireApproval, Approval approval) {
        return switch (current) {
            case PENDING -> new Decision(Action.CREATE_CANARY, Phase.CANARY);
            case CANARY -> new Decision(Action.CREATE_JOB, Phase.EVALUATING);
            case EVALUATING -> switch (outcome) {
                case SUCCEEDED -> requireApproval
                        ? new Decision(Action.HOLD, Phase.AWAITING_APPROVAL)
                        : new Decision(Action.PROMOTE, Phase.PROMOTED);
                case FAILED -> new Decision(Action.ROLLBACK, Phase.ROLLED_BACK);
                // A gate that never reports still terminates: time out into a rollback.
                case NONE, RUNNING -> deadlineExceeded
                        ? new Decision(Action.ROLLBACK, Phase.ROLLED_BACK)
                        : new Decision(Action.WAIT, Phase.EVALUATING);
            };
            // No deadline here: a held promotion waits as long as the human needs.
            case AWAITING_APPROVAL -> switch (approval) {
                case APPROVED -> new Decision(Action.PROMOTE, Phase.PROMOTED);
                case REJECTED -> new Decision(Action.ROLLBACK, Phase.ROLLED_BACK);
                case NONE -> new Decision(Action.WAIT, Phase.AWAITING_APPROVAL);
            };
            case PROMOTED -> new Decision(Action.DONE, Phase.PROMOTED);
            case ROLLED_BACK -> new Decision(Action.DONE, Phase.ROLLED_BACK);
        };
    }
}
