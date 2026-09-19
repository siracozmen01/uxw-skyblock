package com.uxplima.uxmskyblock.core.domain.account;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Canonical root domain aggregate representing a physical player account identified by UUID.
 */
public record PlayerAccount(PlayerUuid playerUuid, Instant createdAt, Instant lastSeenAt) {

    public PlayerAccount {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
    }

    public static PlayerAccount create(PlayerUuid playerUuid, Instant now) {
        return new PlayerAccount(playerUuid, now, now);
    }

    public PlayerAccount withLastSeen(Instant now) {
        return new PlayerAccount(playerUuid, createdAt, now);
    }
}
