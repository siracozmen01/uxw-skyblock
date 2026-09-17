package com.uxplima.uxmskyblock.api;

import java.util.Objects;

/**
 * Sealed algebraic data type representing the result of an API operation.
 *
 * @param <T> success value type
 */
public sealed interface IslandResult<T> {

    record Success<T>(T value) implements IslandResult<T> {
        public Success {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    record Failure<T>(String reason) implements IslandResult<T> {
        public Failure {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    record FeatureUnavailable<T>(String featureKey) implements IslandResult<T> {
        public FeatureUnavailable {
            Objects.requireNonNull(featureKey, "featureKey must not be null");
        }
    }

    static <T> IslandResult<T> success(T value) {
        return new Success<>(value);
    }

    static <T> IslandResult<T> failure(String reason) {
        return new Failure<>(reason);
    }

    static <T> IslandResult<T> unavailable(String featureKey) {
        return new FeatureUnavailable<>(featureKey);
    }
}
