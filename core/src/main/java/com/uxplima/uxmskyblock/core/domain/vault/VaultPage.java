package com.uxplima.uxmskyblock.core.domain.vault;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Represents a persisted page of virtual island storage.
 *
 * @param islandId target island ID
 * @param page 1-based page index
 * @param pageVersion OCC version monotonically incremented on commit
 * @param leaseEpoch fencing token monotonically incremented on lease acquisition
 * @param activeSessionId active editor's session ID or null if unlocked
 * @param contentsNbt serialized item contents
 * @param lastModifiedBy profile or actor ID of the last modifying member
 * @param updatedAt timestamp of the last mutation
 */
@SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
public record VaultPage(
        IslandId islandId,
        int page,
        long pageVersion,
        long leaseEpoch,
        @Nullable VaultSessionId activeSessionId,
        byte[] contentsNbt,
        String lastModifiedBy,
        Instant updatedAt) {

    public VaultPage {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1: " + page);
        }
        if (pageVersion < 1) {
            throw new IllegalArgumentException("pageVersion must be >= 1: " + pageVersion);
        }
        if (leaseEpoch < 1) {
            throw new IllegalArgumentException("leaseEpoch must be >= 1: " + leaseEpoch);
        }
        Objects.requireNonNull(contentsNbt, "contentsNbt must not be null");
        Objects.requireNonNull(lastModifiedBy, "lastModifiedBy must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        contentsNbt = contentsNbt.clone();
    }

    public static VaultPage initial(IslandId islandId, int page, byte[] contentsNbt, String creator) {
        return new VaultPage(islandId, page, 1L, 1L, null, contentsNbt, creator, Instant.now());
    }

    @Override
    public byte[] contentsNbt() {
        return contentsNbt.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VaultPage that)) return false;
        return page == that.page
                && pageVersion == that.pageVersion
                && leaseEpoch == that.leaseEpoch
                && Objects.equals(islandId, that.islandId)
                && Objects.equals(activeSessionId, that.activeSessionId)
                && Arrays.equals(contentsNbt, that.contentsNbt)
                && Objects.equals(lastModifiedBy, that.lastModifiedBy)
                && Objects.equals(updatedAt, that.updatedAt);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(islandId, page, pageVersion, leaseEpoch, activeSessionId, lastModifiedBy, updatedAt);
        result = 31 * result + Arrays.hashCode(contentsNbt);
        return result;
    }
}
