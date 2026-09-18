package com.uxplima.uxmskyblock.core.domain.economy;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a durable economy saga transaction.
 */
public record SagaId(String value) {

    public SagaId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static SagaId random() {
        return new SagaId(UUID.randomUUID().toString());
    }

    public static SagaId of(String value) {
        return new SagaId(value);
    }
}
