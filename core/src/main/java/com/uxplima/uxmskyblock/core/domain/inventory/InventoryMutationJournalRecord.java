package com.uxplima.uxmskyblock.core.domain.inventory;

import java.time.Instant;
import java.util.Objects;

/**
 * Read model record representing a persisted entry in {@code inventory_mutation_journals}.
 */
public record InventoryMutationJournalRecord(
        InventoryMutationOperationId operationId,
        String operationType,
        InventoryMutationJournalState state,
        int participantCount,
        String payload,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public InventoryMutationJournalRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
