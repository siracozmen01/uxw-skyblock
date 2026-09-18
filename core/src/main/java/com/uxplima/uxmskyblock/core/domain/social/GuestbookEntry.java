package com.uxplima.uxmskyblock.core.domain.social;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Immutable domain record representing an interactive guestbook review note left on a subject.
 */
public record GuestbookEntry(
        String reviewId,
        SocialSubjectRef subject,
        ProfileId authorProfileId,
        String message,
        boolean isHidden,
        boolean isPinned,
        Instant createdAt) {

    public GuestbookEntry {
        Objects.requireNonNull(reviewId, "reviewId cannot be null");
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(authorProfileId, "authorProfileId cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
        if (reviewId.isBlank()) {
            throw new IllegalArgumentException("reviewId cannot be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message cannot be blank");
        }
    }
}
