package com.uxplima.uxmskyblock.core.domain.recycle;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable cryptographic reset verification challenge issued to a player requesting
 * island deletion or reset.
 *
 * @param code 4-digit verification code string
 * @param expiresAt timestamp when this challenge expires
 */
public record ResetChallenge(String code, Instant expiresAt) {

    public ResetChallenge {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
