package com.uxplima.uxmskyblock.core.domain.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when an island member attempts to open a vault page currently locked by another member.
 */
public final class VaultPageBusyException extends RuntimeException {

    private final IslandId islandId;
    private final int page;
    private final UUID activeEditorUuid;
    private final Instant leaseExpiresAt;

    public VaultPageBusyException(IslandId islandId, int page, UUID activeEditorUuid, Instant leaseExpiresAt) {
        super("Vault page " + page + " on island " + islandId + " is currently locked by " + activeEditorUuid);
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.page = page;
        this.activeEditorUuid = Objects.requireNonNull(activeEditorUuid, "activeEditorUuid must not be null");
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt must not be null");
    }

    public IslandId islandId() {
        return islandId;
    }

    public int page() {
        return page;
    }

    public UUID activeEditorUuid() {
        return activeEditorUuid;
    }

    public Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }
}
