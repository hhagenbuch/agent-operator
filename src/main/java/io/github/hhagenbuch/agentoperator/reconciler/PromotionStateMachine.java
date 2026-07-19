package io.github.hhagenbuch.agentoperator.reconciler;

/**
 * The PromptVersion promotion state machine as a pure function:
 * {@code (currentPhase, evalJobOutcome) → (action, nextPhase)}. Keeping the
 * decision separate from cluster I/O makes the whole promotion/rollback policy
 * exhaustively unit-testable — the reconciler just executes the returned action.
 *
 * <pre>
 * Pending ──► Canary ──► Evaluating ──► Promoted
 *                            │
 *                            └────────► RolledBack
 * </pre>
 */
public final class PromotionStateMachine {

    public enum Phase {
        PENDING("Pending"),
        CANARY("Canary"),
        EVALUATING("Evaluating"),
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

    public enum Action {
        CREATE_CANARY, CREATE_JOB, WAIT, PROMOTE, ROLLBACK, DONE
    }

    public record Decision(Action action, Phase nextPhase) {
    }

    private PromotionStateMachine() {
    }

    public static Decision decide(Phase current, JobOutcome outcome) {
        return switch (current) {
            case PENDING -> new Decision(Action.CREATE_CANARY, Phase.CANARY);
            case CANARY -> new Decision(Action.CREATE_JOB, Phase.EVALUATING);
            case EVALUATING -> switch (outcome) {
                case SUCCEEDED -> new Decision(Action.PROMOTE, Phase.PROMOTED);
                case FAILED -> new Decision(Action.ROLLBACK, Phase.ROLLED_BACK);
                case NONE, RUNNING -> new Decision(Action.WAIT, Phase.EVALUATING);
            };
            case PROMOTED -> new Decision(Action.DONE, Phase.PROMOTED);
            case ROLLED_BACK -> new Decision(Action.DONE, Phase.ROLLED_BACK);
        };
    }
}
