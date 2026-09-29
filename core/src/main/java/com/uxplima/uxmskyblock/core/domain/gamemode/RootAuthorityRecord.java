package com.uxplima.uxmskyblock.core.domain.gamemode;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Who writes to a root that is not an island, under which epoch, and until when.
 *
 * @param leaseExpiresAt when the lease runs out, on this machine's clock
 */
public record RootAuthorityRecord(
        AuthorityRoot root, ServerNodeId authoritativeNode, long authorityEpoch, Instant leaseExpiresAt) {

    public RootAuthorityRecord {
        Objects.requireNonNull(root, "root must not be null");
        Objects.requireNonNull(authoritativeNode, "authoritativeNode must not be null");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    }

    /** Whether the lease still runs at {@code now}. */
    public boolean isLive(Instant now) {
        return leaseExpiresAt.isAfter(now);
    }
}
