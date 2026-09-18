package com.uxplima.uxmskyblock.core.domain.reward;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Pure domain aggregate representing a durable reward grant in the player's offline inbox.
 *
 * <p>Lifecycle: {@code PENDING} &rarr; {@code CLAIMING} &rarr; {@code CLAIMED} (or {@code EXPIRED}, {@code RECOVERY_REQUIRED}).
 * The parent grant transitions to {@code CLAIMED} only after all required components are durably completed (COMMITTED).
 *
 * @param grantId unique aggregate identifier
 * @param recipientProfileId profile ID entitled to claim this reward
 * @param sourceType source domain emitting the grant (e.g. "SEASON_PAYOUT", "ADMIN_GRANT", "QUEST")
 * @param sourceId external or business reference ID within the source domain
 * @param state current lifecycle state
 * @param components ordered list of delivery components
 * @param claimedAt timestamp when claim fully completed
 * @param expiresAt optional expiration timestamp
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record RewardGrant(
        RewardGrantId grantId,
        ProfileId recipientProfileId,
        String sourceType,
        String sourceId,
        RewardGrantState state,
        List<RewardGrantComponent> components,
        @Nullable Instant claimedAt,
        @Nullable Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public RewardGrant {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(components, "components must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // Validate component invariants
        Set<Integer> seenIndexes = new HashSet<>();
        Set<RewardComponentOperationId> seenOps = new HashSet<>();

        for (RewardGrantComponent comp : components) {
            if (!comp.grantId().equals(grantId)) {
                throw new IllegalArgumentException(
                        "Component grant ID mismatch: component=" + comp.grantId() + ", grant=" + grantId);
            }
            if (!seenIndexes.add(comp.componentIndex())) {
                throw new IllegalArgumentException("Duplicate component index: " + comp.componentIndex());
            }
            if (!seenOps.add(comp.componentOperationId())) {
                throw new IllegalArgumentException("Duplicate component operation ID: " + comp.componentOperationId());
            }
            RewardComponentOperationId expectedOpId = RewardComponentOperationId.derive(grantId, comp.componentIndex());
            if (!comp.componentOperationId().equals(expectedOpId)) {
                throw new IllegalArgumentException(
                        "Component operation ID must be derived from (grantId + componentIndex): "
                                + comp.componentOperationId() + " != " + expectedOpId);
            }
        }

        components = Collections.unmodifiableList(new ArrayList<>(components));
    }

    public Optional<Instant> optClaimedAt() {
        return Optional.ofNullable(claimedAt);
    }

    public Optional<Instant> optExpiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /**
     * Checks if all components in this grant have reached COMMITTED state.
     */
    public boolean isAllComponentsCommitted() {
        return !components.isEmpty() && components.stream().allMatch(RewardGrantComponent::isCommitted);
    }

    /**
     * Checks if this grant has expired given a reference timestamp.
     */
    public boolean isExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /**
     * Transitions the grant to a new state.
     */
    public RewardGrant withState(RewardGrantState newState, Instant now) {
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new RewardGrant(
                grantId,
                recipientProfileId,
                sourceType,
                sourceId,
                newState,
                components,
                claimedAt,
                expiresAt,
                createdAt,
                now);
    }

    /**
     * Transitions the grant to CLAIMED state with the specified claimed timestamp.
     */
    public RewardGrant withClaimed(Instant claimedTimestamp, Instant now) {
        Objects.requireNonNull(claimedTimestamp, "claimedTimestamp must not be null");
        Objects.requireNonNull(now, "now must not be null");
        return new RewardGrant(
                grantId,
                recipientProfileId,
                sourceType,
                sourceId,
                RewardGrantState.CLAIMED,
                components,
                claimedTimestamp,
                expiresAt,
                createdAt,
                now);
    }

    /**
     * Replaces an existing component in this grant with an updated component record.
     */
    public RewardGrant withComponent(RewardGrantComponent updatedComponent, Instant now) {
        Objects.requireNonNull(updatedComponent, "updatedComponent must not be null");
        Objects.requireNonNull(now, "now must not be null");
        List<RewardGrantComponent> updatedList = new ArrayList<>(components.size());
        boolean found = false;
        for (RewardGrantComponent c : components) {
            if (c.componentIndex() == updatedComponent.componentIndex()) {
                updatedList.add(updatedComponent);
                found = true;
            } else {
                updatedList.add(c);
            }
        }
        if (!found) {
            throw new IllegalArgumentException(
                    "Component index not found in grant: " + updatedComponent.componentIndex());
        }
        return new RewardGrant(
                grantId,
                recipientProfileId,
                sourceType,
                sourceId,
                state,
                updatedList,
                claimedAt,
                expiresAt,
                createdAt,
                now);
    }
}
