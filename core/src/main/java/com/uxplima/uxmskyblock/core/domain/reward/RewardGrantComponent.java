package com.uxplima.uxmskyblock.core.domain.reward;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Entity representing an individual delivery component within a {@link RewardGrant}.
 *
 * @param componentId primary key UUID of this component
 * @param grantId parent reward grant ID
 * @param componentIndex zero-based sequential index of this component in the grant
 * @param componentOperationId stable, deterministic idempotency operation ID for this component
 * @param componentType protocol category for delivery
 * @param payloadTypeId namespaced payload identifier (e.g. "uxm:item_bundle", "uxm:currency_deposit")
 * @param payloadSchemaVersion schema version of the payload data
 * @param payloadData serialized JSON or string payload payload
 * @param state delivery state of this component
 * @param journalOperationId durable journal or saga operation ID associated with successful delivery
 * @param updatedAt timestamp of last state update
 */
public record RewardGrantComponent(
        UUID componentId,
        RewardGrantId grantId,
        int componentIndex,
        RewardComponentOperationId componentOperationId,
        RewardComponentType componentType,
        String payloadTypeId,
        int payloadSchemaVersion,
        String payloadData,
        RewardComponentState state,
        @Nullable UUID journalOperationId,
        Instant updatedAt) {

    public RewardGrantComponent {
        Objects.requireNonNull(componentId, "componentId must not be null");
        Objects.requireNonNull(grantId, "grantId must not be null");
        if (componentIndex < 0) {
            throw new IllegalArgumentException("componentIndex must be non-negative: " + componentIndex);
        }
        Objects.requireNonNull(componentOperationId, "componentOperationId must not be null");
        Objects.requireNonNull(componentType, "componentType must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        if (payloadTypeId.isBlank()) {
            throw new IllegalArgumentException("payloadTypeId must not be blank");
        }
        if (payloadSchemaVersion < 1) {
            throw new IllegalArgumentException("payloadSchemaVersion must be at least 1: " + payloadSchemaVersion);
        }
        Objects.requireNonNull(payloadData, "payloadData must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /**
     * Checks if this component has been durably committed to the destination protocol.
     */
    public boolean isCommitted() {
        return state == RewardComponentState.COMMITTED;
    }

    /**
     * Optional accessor for journalOperationId.
     */
    public Optional<UUID> optJournalOperationId() {
        return Optional.ofNullable(journalOperationId);
    }

    /**
     * Returns an updated component with COMMITTED state and recorded journal operation ID.
     */
    public RewardGrantComponent withCommitted(@Nullable UUID journalOpId, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new RewardGrantComponent(
                componentId,
                grantId,
                componentIndex,
                componentOperationId,
                componentType,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                RewardComponentState.COMMITTED,
                journalOpId != null ? journalOpId : componentOperationId.value(),
                now);
    }

    /**
     * Returns an updated component with FAILED state.
     */
    public RewardGrantComponent withFailed(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new RewardGrantComponent(
                componentId,
                grantId,
                componentIndex,
                componentOperationId,
                componentType,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                RewardComponentState.FAILED,
                journalOperationId,
                now);
    }
}
