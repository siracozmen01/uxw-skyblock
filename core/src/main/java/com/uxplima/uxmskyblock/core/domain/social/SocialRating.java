package com.uxplima.uxmskyblock.core.domain.social;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Immutable domain record representing an individual player profile's rating for a social subject.
 */
public record SocialRating(
        SocialSubjectRef subject, ProfileId raterProfileId, int score, Instant createdAt, Instant updatedAt) {

    public SocialRating {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(raterProfileId, "raterProfileId cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
    }
}
