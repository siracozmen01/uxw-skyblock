package com.uxplima.uxmskyblock.core.application.reward;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;

/**
 * Draft definition of a reward component prior to aggregate assignment and index derivation.
 *
 * @param componentType protocol category
 * @param payloadTypeId namespaced payload identifier
 * @param payloadSchemaVersion schema version
 * @param payloadData serialized payload content
 */
public record RewardDraftComponent(
        RewardComponentType componentType, String payloadTypeId, int payloadSchemaVersion, String payloadData) {

    public RewardDraftComponent {
        Objects.requireNonNull(componentType, "componentType must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        if (payloadTypeId.isBlank()) {
            throw new IllegalArgumentException("payloadTypeId must not be blank");
        }
        if (payloadSchemaVersion < 1) {
            throw new IllegalArgumentException("payloadSchemaVersion must be at least 1: " + payloadSchemaVersion);
        }
        Objects.requireNonNull(payloadData, "payloadData must not be null");
    }
}
