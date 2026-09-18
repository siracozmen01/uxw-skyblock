package com.uxplima.uxmskyblock.core.domain.vault;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Immutable rolling audit log entry for vault item movements.
 */
public record VaultAuditLogEntry(
        UUID logId,
        IslandId islandId,
        int page,
        String actorProfileId,
        VaultActionType actionType,
        int slot,
        String itemSummary,
        int quantity,
        Instant createdAt) {

    public VaultAuditLogEntry {
        Objects.requireNonNull(logId, "logId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1: " + page);
        }
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(itemSummary, "itemSummary must not be null");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1: " + quantity);
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static VaultAuditLogEntry create(
            IslandId islandId,
            int page,
            String actorProfileId,
            VaultActionType actionType,
            int slot,
            String itemSummary,
            int quantity) {
        return new VaultAuditLogEntry(
                UUID.randomUUID(),
                islandId,
                page,
                actorProfileId,
                actionType,
                slot,
                itemSummary,
                quantity,
                Instant.now());
    }
}
