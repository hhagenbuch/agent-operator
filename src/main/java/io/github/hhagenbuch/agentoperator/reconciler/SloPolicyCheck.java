package io.github.hhagenbuch.agentoperator.reconciler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure error-budget arithmetic for the continuous-eval-pass-rate SLI
 * (the agent-slo RFC's proving slice). Samples are eval-run results appended by
 * the scheduled runner; {@link #evaluate} turns the in-window samples into a
 * freeze decision. Like {@link PromotionStateMachine}, this class holds no
 * cluster state so the policy is unit-testable in isolation.
 *
 * <p>Freeze semantics (RFC §2.3): the freeze trips when the window's error
 * budget is fully consumed and exits with hysteresis — only once consumption
 * falls back below the {@code UNFREEZE_BELOW} approval threshold — so the gate
 * cannot flap at the trip point. Windows holding fewer than {@code minSamples}
 * valid events are not actionable (RFC §3.3): they never trip the freeze, and
 * never lift one either.
 */
public final class SloPolicyCheck {

    /** Budget-consumed fraction below which a standing freeze lifts (the approval-ladder step). */
    public static final double UNFREEZE_BELOW = 0.75;
    /** Exempt annotation: a PromptVersion carrying this value passes the freeze (never the gate). */
    public static final String EXEMPT_VALUE = "fix";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SloPolicyCheck() {
    }

    /** One scheduled eval run: {@code passed} of {@code total} cases at {@code ts}. */
    public record SloSample(Instant ts, int passed, int total) {
    }

    /** The policy outcome for one evaluation pass. */
    public record SloVerdict(boolean actionable, int validEvents, int failures,
                             double budgetConsumed, boolean frozen, String message) {
    }

    /**
     * Parses JSONL samples ({@code {"ts":"...","passed":N,"total":M}} per line).
     * Malformed or inconsistent lines are skipped, not failed: a corrupt sample
     * must degrade measurement coverage, never crash the reconcile loop.
     */
    public static List<SloSample> parseSamples(String jsonl) {
        List<SloSample> samples = new ArrayList<>();
        if (jsonl == null || jsonl.isBlank()) {
            return samples;
        }
        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = MAPPER.readTree(line);
                Instant ts = Instant.parse(node.get("ts").asText());
                int passed = node.get("passed").asInt();
                int total = node.get("total").asInt();
                if (total > 0 && passed >= 0 && passed <= total) {
                    samples.add(new SloSample(ts, passed, total));
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JacksonException e) {
                // skip — see javadoc
            }
        }
        return samples;
    }

    /** Parses {@code 7d} / {@code 12h} / {@code 5m} / {@code 30s} window strings. */
    public static Duration parseWindow(String window) {
        if (window == null || window.length() < 2) {
            throw new IllegalArgumentException("window must look like 7d/12h/5m/30s, got: " + window);
        }
        long amount = Long.parseLong(window.substring(0, window.length() - 1));
        return switch (window.charAt(window.length() - 1)) {
            case 'd' -> Duration.ofDays(amount);
            case 'h' -> Duration.ofHours(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 's' -> Duration.ofSeconds(amount);
            default -> throw new IllegalArgumentException(
                    "window must end in d/h/m/s, got: " + window);
        };
    }

    public static SloVerdict evaluate(List<SloSample> samples, Instant now, Duration window,
                                      double target, int minSamples, boolean currentlyFrozen) {
        Instant cutoff = now.minus(window);
        int validEvents = 0;
        int failures = 0;
        for (SloSample sample : samples) {
            if (!sample.ts().isBefore(cutoff)) {
                validEvents += sample.total();
                failures += sample.total() - sample.passed();
            }
        }

        if (validEvents < minSamples) {
            return new SloVerdict(false, validEvents, failures, 0.0, currentlyFrozen,
                    "insufficient data: " + validEvents + "/" + minSamples
                            + " valid events in window — freeze state held, not advanced");
        }

        double budget = (1.0 - target) * validEvents;
        double consumed = budget <= 0
                ? (failures > 0 ? Double.POSITIVE_INFINITY : 0.0)
                : failures / budget;
        boolean frozen = currentlyFrozen ? consumed >= UNFREEZE_BELOW : consumed >= 1.0;

        double passRate = 1.0 - (double) failures / validEvents;
        String message = String.format(
                "pass rate %.3f over %d cases (target %.3f); error budget %.0f%% consumed — %s",
                passRate, validEvents, target, Math.min(consumed, 99.0) * 100,
                frozen ? "promotions FROZEN" : "promotions open");
        return new SloVerdict(true, validEvents, failures, consumed, frozen, message);
    }

    /**
     * Whether a new promotion must be refused: the Agent is frozen and the
     * PromptVersion does not carry the {@code slo-exempt: fix} assertion.
     * Exemption skips the freeze, never the eval gate.
     */
    public static boolean refusesPromotion(Boolean promotionsFrozen, String exemptAnnotation) {
        return Boolean.TRUE.equals(promotionsFrozen) && !EXEMPT_VALUE.equals(exemptAnnotation);
    }
}
