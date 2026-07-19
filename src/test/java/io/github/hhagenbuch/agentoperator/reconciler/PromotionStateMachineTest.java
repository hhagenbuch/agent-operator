package io.github.hhagenbuch.agentoperator.reconciler;

import io.github.hhagenbuch.agentoperator.reconciler.PromotionStateMachine.Action;
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
        assertThat(decide(Phase.PENDING, JobOutcome.NONE))
                .isEqualTo(new Decision(Action.CREATE_CANARY, Phase.CANARY));
    }

    @Test
    void canaryLaunchesTheEvalJob() {
        assertThat(decide(Phase.CANARY, JobOutcome.NONE))
                .isEqualTo(new Decision(Action.CREATE_JOB, Phase.EVALUATING));
    }

    @ParameterizedTest
    @EnumSource(value = JobOutcome.class, names = {"NONE", "RUNNING"})
    void evaluatingWaitsWhileTheJobRuns(JobOutcome outcome) {
        assertThat(decide(Phase.EVALUATING, outcome))
                .isEqualTo(new Decision(Action.WAIT, Phase.EVALUATING));
    }

    @Test
    void passingEvalPromotes() {
        assertThat(decide(Phase.EVALUATING, JobOutcome.SUCCEEDED))
                .isEqualTo(new Decision(Action.PROMOTE, Phase.PROMOTED));
    }

    @Test
    void failingEvalRollsBack() {
        assertThat(decide(Phase.EVALUATING, JobOutcome.FAILED))
                .isEqualTo(new Decision(Action.ROLLBACK, Phase.ROLLED_BACK));
    }

    @ParameterizedTest
    @EnumSource(JobOutcome.class)
    void terminalPhasesStayPut(JobOutcome outcome) {
        assertThat(decide(Phase.PROMOTED, outcome).action()).isEqualTo(Action.DONE);
        assertThat(decide(Phase.ROLLED_BACK, outcome).action()).isEqualTo(Action.DONE);
    }

    @Test
    void phaseParsesFromStatusStringDefaultingToPending() {
        assertThat(Phase.fromStatus(null)).isEqualTo(Phase.PENDING);
        assertThat(Phase.fromStatus("Evaluating")).isEqualTo(Phase.EVALUATING);
        assertThat(Phase.fromStatus("RolledBack")).isEqualTo(Phase.ROLLED_BACK);
        assertThat(Phase.fromStatus("nonsense")).isEqualTo(Phase.PENDING);
    }
}
