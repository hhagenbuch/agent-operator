package io.github.hhagenbuch.agentoperator.reconciler;

import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Action;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Approval;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Decision;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.JobOutcome;
import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.decide;
import static org.assertj.core.api.Assertions.assertThat;

class PromotionStateMachineTest {

    @Test
    void pendingCreatesTheCanary() {
        assertThat(decide(Phase.PENDING, JobOutcome.NONE, false))
                .isEqualTo(new Decision(Action.CREATE_CANARY, Phase.CANARY));
    }

    @Test
    void canaryLaunchesTheEvalJob() {
        assertThat(decide(Phase.CANARY, JobOutcome.NONE, false))
                .isEqualTo(new Decision(Action.CREATE_JOB, Phase.EVALUATING));
    }

    @ParameterizedTest
    @EnumSource(value = JobOutcome.class, names = {"NONE", "RUNNING"})
    void evaluatingWaitsWhileTheJobRuns(JobOutcome outcome) {
        assertThat(decide(Phase.EVALUATING, outcome, false))
                .isEqualTo(new Decision(Action.WAIT, Phase.EVALUATING));
    }

    @ParameterizedTest
    @EnumSource(value = JobOutcome.class, names = {"NONE", "RUNNING"})
    void evaluatingTimesOutIntoRollbackWhenTheJobNeverReports(JobOutcome outcome) {
        assertThat(decide(Phase.EVALUATING, outcome, true))
                .isEqualTo(new Decision(Action.ROLLBACK, Phase.ROLLED_BACK));
    }

    @Test
    void passingEvalPromotesEvenIfTheDeadlineIsAlsoUp() {
        assertThat(decide(Phase.EVALUATING, JobOutcome.SUCCEEDED, true))
                .isEqualTo(new Decision(Action.PROMOTE, Phase.PROMOTED));
    }

    @Test
    void failingEvalRollsBack() {
        assertThat(decide(Phase.EVALUATING, JobOutcome.FAILED, false))
                .isEqualTo(new Decision(Action.ROLLBACK, Phase.ROLLED_BACK));
    }

    @ParameterizedTest
    @EnumSource(JobOutcome.class)
    void terminalPhasesStayPut(JobOutcome outcome) {
        assertThat(decide(Phase.PROMOTED, outcome, false).action()).isEqualTo(Action.DONE);
        assertThat(decide(Phase.ROLLED_BACK, outcome, true).action()).isEqualTo(Action.DONE);
    }

    @Test
    void passingEvalHoldsWhenApprovalIsRequired() {
        assertThat(decide(Phase.EVALUATING, JobOutcome.SUCCEEDED, false, true, Approval.NONE))
                .isEqualTo(new Decision(Action.HOLD, Phase.AWAITING_APPROVAL));
    }

    @Test
    void failingEvalRollsBackEvenWhenApprovalIsRequired() {
        // Approval gates promotions, never failures — a failed gate needs no human.
        assertThat(decide(Phase.EVALUATING, JobOutcome.FAILED, false, true, Approval.NONE))
                .isEqualTo(new Decision(Action.ROLLBACK, Phase.ROLLED_BACK));
    }

    @Test
    void awaitingApprovalWaitsIndefinitely() {
        // The gate deadline must not expire a human's decision window.
        assertThat(decide(Phase.AWAITING_APPROVAL, JobOutcome.SUCCEEDED, true, true, Approval.NONE))
                .isEqualTo(new Decision(Action.WAIT, Phase.AWAITING_APPROVAL));
    }

    @Test
    void approvalPromotes() {
        assertThat(decide(Phase.AWAITING_APPROVAL, JobOutcome.SUCCEEDED, false, true, Approval.APPROVED))
                .isEqualTo(new Decision(Action.PROMOTE, Phase.PROMOTED));
    }

    @Test
    void rejectionRollsBack() {
        assertThat(decide(Phase.AWAITING_APPROVAL, JobOutcome.SUCCEEDED, false, true, Approval.REJECTED))
                .isEqualTo(new Decision(Action.ROLLBACK, Phase.ROLLED_BACK));
    }

    @Test
    void threeArgOverloadKeepsTheAutoPromoteBehavior() {
        assertThat(decide(Phase.EVALUATING, JobOutcome.SUCCEEDED, false))
                .isEqualTo(new Decision(Action.PROMOTE, Phase.PROMOTED));
    }

    @Test
    void phaseParsesFromStatusStringDefaultingToPending() {
        assertThat(Phase.fromStatus(null)).isEqualTo(Phase.PENDING);
        assertThat(Phase.fromStatus("Evaluating")).isEqualTo(Phase.EVALUATING);
        assertThat(Phase.fromStatus("AwaitingApproval")).isEqualTo(Phase.AWAITING_APPROVAL);
        assertThat(Phase.fromStatus("RolledBack")).isEqualTo(Phase.ROLLED_BACK);
        assertThat(Phase.fromStatus("nonsense")).isEqualTo(Phase.PENDING);
    }
}
