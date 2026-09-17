package com.uxplima.uxmskyblock.core.domain.inventory;

import java.util.Objects;
import java.util.UUID;

/**
 * Pure value object representing the unique operation identifier / idempotency key
 * for an economic inventory mutation.
 */
public record InventoryMutationOperationId(UUID value) {

    public InventoryMutationOperationId {
        Objects.requireNonNull(value, "value");
    }

    public static InventoryMutationOperationId of(UUID value) {
        return new InventoryMutationOperationId(value);
    }

    public static InventoryMutationOperationId of(String string) {
        Objects.requireNonNull(string, "string");
        return new InventoryMutationOperationId(UUID.fromString(string));
    }

    public static InventoryMutationOperationId fromString(String string) {
        return of(string);
    }

    public static InventoryMutationOperationId random() {
        return new InventoryMutationOperationId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
