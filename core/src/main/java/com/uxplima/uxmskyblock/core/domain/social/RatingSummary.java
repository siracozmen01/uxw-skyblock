package com.uxplima.uxmskyblock.core.domain.social;

import java.util.Objects;

/**
 * Immutable aggregated rating summary including raw average and Bayesian weighted score.
 */
public record RatingSummary(SocialSubjectRef subject, int totalRatings, double averageScore, double bayesianScore) {

    public RatingSummary {
        Objects.requireNonNull(subject, "subject cannot be null");
        if (totalRatings < 0) {
            throw new IllegalArgumentException("totalRatings cannot be negative: " + totalRatings);
        }
    }

    public static RatingSummary empty(SocialSubjectRef subject) {
        return new RatingSummary(subject, 0, 0.0, 0.0);
    }
}
