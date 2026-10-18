package com.company.secscan.scoring;

import java.util.List;

/**
 * The outcome of scoring one finding: the clamped 0–100 {@link #score}, its {@link #band} (SPEC bands), the
 * itemized {@link #breakdown} (the deltas sum to {@code score}), and {@link #baseSource} — {@code vector},
 * {@code label} or {@code secret} — recording where the base came from (D-044).
 */
public record ScoreResult(int score, String band, List<ScoreLine> breakdown, String baseSource) {
}
