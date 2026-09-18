package com.uxplima.uxmskyblock.core.domain.social;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Immutable domain record tracking visitor engagement metrics on a discoverable social subject.
 */
public record SubjectVisit(
        SocialSubjectRef subject,
        ProfileId visitorProfileId,
        int visitCount,
        Instant firstVisitedAt,
        Instant lastVisitedAt) {

    public SubjectVisit {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(visitorProfileId, "visitorProfileId cannot be null");
        Objects.requireNonNull(firstVisitedAt, "firstVisitedAt cannot be null");
        Objects.requireNonNull(lastVisitedAt, "lastVisitedAt cannot be null");
        if (visitCount <= 0) {
            throw new IllegalArgumentException("visitCount must be positive: " + visitCount);
        }
    }
}
