package io.github.hhagenbuch.agentoperator.reconciler;

import io.github.hhagenbuch.agentoperator.reconciler.SloPolicyCheck.SloSample;
import io.github.hhagenbuch.agentoperator.reconciler.SloPolicyCheck.SloVerdict;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SloPolicyCheckTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);
    private static final double TARGET = 0.95;

    private static SloSample run(int minutesAgo, int passed, int total) {
        return new SloSample(NOW.minus(Duration.ofMinutes(minutesAgo)), passed, total);
    }

    // --- evaluate: trip, hold, hysteresis ---

    @Test
    void budgetExhaustionTripsTheFreeze() {
        // 30 cases, 10 failures; budget = 0.05 * 30 = 1.5 — consumed ~6.7x
        List<SloSample> samples = List.of(run(10, 4, 6), run(8, 4, 6), run(6, 4, 6),
                run(4, 4, 6), run(2, 4, 6));

        SloVerdict verdict = SloPolicyCheck.evaluate(samples, NOW, WEEK, TARGET, 20, false);

        assertThat(verdict.actionable()).isTrue();
        assertThat(verdict.validEvents()).isEqualTo(30);
        assertThat(verdict.failures()).isEqualTo(10);
        assertThat(verdict.frozen()).isTrue();
        assertThat(verdict.message()).contains("FROZEN");
    }

    @Test
    void withinBudgetStaysOpen() {
        // 40 cases, 1 failure; budget = 2 — half consumed
        SloVerdict verdict = SloPolicyCheck.evaluate(
                List.of(run(10, 20, 20), run(5, 19, 20)), NOW, WEEK, TARGET, 20, false);

        assertThat(verdict.frozen()).isFalse();
        assertThat(verdict.budgetConsumed()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void freezeHoldsUntilConsumptionFallsBelowTheApprovalThreshold() {
        // 100 cases, 4 failures; budget = 5 — consumed 0.8: below trip (1.0) but
        // above unfreeze (0.75). A standing freeze must hold; a fresh one must not trip.
        List<SloSample> samples = List.of(run(30, 96, 100));

        assertThat(SloPolicyCheck.evaluate(samples, NOW, WEEK, TARGET, 20, true).frozen())
                .as("standing freeze holds in the hysteresis band").isTrue();
        assertThat(SloPolicyCheck.evaluate(samples, NOW, WEEK, TARGET, 20, false).frozen())
                .as("no fresh trip below full exhaustion").isFalse();
    }

    @Test
    void freezeLiftsOnceConsumptionDropsBelowThreshold() {
        // 100 cases, 3 failures; consumed 0.6 < 0.75
        SloVerdict verdict = SloPolicyCheck.evaluate(
                List.of(run(30, 97, 100)), NOW, WEEK, TARGET, 20, true);

        assertThat(verdict.frozen()).isFalse();
    }

    @Test
    void samplesOutsideTheWindowAgeOut() {
        // The failures all sit outside a 5m window; only the clean run counts.
        List<SloSample> samples = List.of(
                run(60, 0, 30),   // catastrophic, but an hour old
                run(2, 25, 25));

        SloVerdict verdict = SloPolicyCheck.evaluate(samples, NOW, Duration.ofMinutes(5),
                TARGET, 20, true);

        assertThat(verdict.validEvents()).isEqualTo(25);
        assertThat(verdict.failures()).isZero();
        assertThat(verdict.frozen()).as("freeze lifts once the bad day rolls out").isFalse();
    }

    // --- evaluate: minimum-evidence rule ---

    @Test
    void insufficientDataNeverTripsTheFreeze() {
        // 12 cases, all failing — but below minSamples 20.
        SloVerdict verdict = SloPolicyCheck.evaluate(
                List.of(run(5, 0, 12)), NOW, WEEK, TARGET, 20, false);

        assertThat(verdict.actionable()).isFalse();
        assertThat(verdict.frozen()).isFalse();
        assertThat(verdict.message()).contains("insufficient data");
    }

    @Test
    void insufficientDataNeverLiftsAFreezeEither() {
        SloVerdict verdict = SloPolicyCheck.evaluate(
                List.of(run(5, 12, 12)), NOW, WEEK, TARGET, 20, true);

        assertThat(verdict.actionable()).isFalse();
        assertThat(verdict.frozen()).as("a blind SLI holds state, it doesn't relax it").isTrue();
    }

    @Test
    void perfectTargetMeansAnyFailureExhaustsTheBudget() {
        SloVerdict verdict = SloPolicyCheck.evaluate(
                List.of(run(5, 24, 25)), NOW, WEEK, 1.0, 20, false);

        assertThat(verdict.budgetConsumed()).isInfinite();
        assertThat(verdict.frozen()).isTrue();
    }

    // --- parseSamples ---

    @Test
    void parsesJsonlAndSkipsGarbage() {
        String jsonl = """
                {"ts":"2026-07-22T11:58:00Z","passed":4,"total":6}
                not json at all
                {"ts":"2026-07-22T11:59:00Z","passed":9,"total":6}
                {"passed":4,"total":6}

                {"ts":"2026-07-22T12:00:00Z","passed":6,"total":6}
                """;

        List<SloSample> samples = SloPolicyCheck.parseSamples(jsonl);

        assertThat(samples).containsExactly(
                new SloSample(Instant.parse("2026-07-22T11:58:00Z"), 4, 6),
                new SloSample(Instant.parse("2026-07-22T12:00:00Z"), 6, 6));
    }

    @Test
    void emptyAndNullSamplesParseToNothing() {
        assertThat(SloPolicyCheck.parseSamples(null)).isEmpty();
        assertThat(SloPolicyCheck.parseSamples("")).isEmpty();
    }

    // --- parseWindow ---

    @Test
    void parsesWindowSuffixes() {
        assertThat(SloPolicyCheck.parseWindow("7d")).isEqualTo(Duration.ofDays(7));
        assertThat(SloPolicyCheck.parseWindow("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(SloPolicyCheck.parseWindow("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(SloPolicyCheck.parseWindow("30s")).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsMalformedWindows() {
        assertThatThrownBy(() -> SloPolicyCheck.parseWindow("7w"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SloPolicyCheck.parseWindow("d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SloPolicyCheck.parseWindow(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- refusesPromotion ---

    @Test
    void refusalRequiresAFreezeAndNoExemption() {
        assertThat(SloPolicyCheck.refusesPromotion(true, null)).isTrue();
        assertThat(SloPolicyCheck.refusesPromotion(true, "fix")).isFalse();
        assertThat(SloPolicyCheck.refusesPromotion(true, "feature")).isTrue();
        assertThat(SloPolicyCheck.refusesPromotion(false, null)).isFalse();
        assertThat(SloPolicyCheck.refusesPromotion(null, null)).isFalse();
    }
}
