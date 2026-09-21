package com.uxplima.uxmskyblock.core.application.reward;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.jspecify.annotations.Nullable;

/**
 * In-memory test double of {@link RewardStoragePort} for unit testing and contract verification.
 */
public final class InMemoryRewardStorage implements RewardStoragePort {

    private final Map<RewardGrantId, RewardGrant> grants = new ConcurrentHashMap<>();
    private final Map<UUID, RewardGrantComponent> components = new ConcurrentHashMap<>();

    @Override
    public void saveGrant(RewardGrant grant) {
        grants.put(grant.grantId(), grant);
        for (RewardGrantComponent c : grant.components()) {
            components.put(c.componentId(), c);
        }
    }

    @Override
    public Optional<RewardGrant> findGrantById(RewardGrantId grantId) {
        RewardGrant grant = grants.get(grantId);
        if (grant == null) {
            return Optional.empty();
        }
        return Optional.of(reconstruct(grant));
    }

    @Override
    public List<RewardGrant> findPendingGrantsByRecipient(ProfileId recipientProfileId) {
        List<RewardGrant> list = new ArrayList<>();
        for (RewardGrant g : grants.values()) {
            if (g.recipientProfileId().equals(recipientProfileId)
                    && (g.state() == RewardGrantState.PENDING
                            || g.state() == RewardGrantState.CLAIMING
                            || g.state() == RewardGrantState.RECOVERY_REQUIRED)) {
                list.add(reconstruct(g));
            }
        }
        list.sort(Comparator.comparing(RewardGrant::createdAt));
        return list;
    }

    @Override
    public List<RewardGrant> findAllGrantsByRecipient(ProfileId recipientProfileId) {
        List<RewardGrant> list = new ArrayList<>();
        for (RewardGrant g : grants.values()) {
            if (g.recipientProfileId().equals(recipientProfileId)) {
                list.add(reconstruct(g));
            }
        }
        list.sort(Comparator.comparing(RewardGrant::createdAt));
        return list;
    }

    @Override
    public boolean compareAndSetGrantState(
            RewardGrantId grantId,
            RewardGrantState expectedState,
            RewardGrantState newState,
            @Nullable Instant claimedAt,
            Instant updatedAt) {
        // The compute is the whole point: two claims must not both see the expected state and both
        // win. A get followed by a put would have exactly the race the real statement refuses.
        RewardGrant moved = grants.computeIfPresent(
                grantId,
                (id, existing) -> existing.state() == expectedState
                        ? new RewardGrant(
                                existing.grantId(),
                                existing.recipientProfileId(),
                                existing.sourceType(),
                                existing.sourceId(),
                                newState,
                                existing.components(),
                                claimedAt != null ? claimedAt : existing.claimedAt(),
                                existing.expiresAt(),
                                existing.createdAt(),
                                updatedAt)
                        : existing);
        return moved != null && moved.state() == newState && moved.updatedAt().equals(updatedAt);
    }

    @Override
    public void updateGrantState(
            RewardGrantId grantId, RewardGrantState state, @Nullable Instant claimedAt, Instant updatedAt) {
        RewardGrant existing = grants.get(grantId);
        if (existing != null) {
            RewardGrant updated = new RewardGrant(
                    existing.grantId(),
                    existing.recipientProfileId(),
                    existing.sourceType(),
                    existing.sourceId(),
                    state,
                    existing.components(),
                    claimedAt != null ? claimedAt : existing.claimedAt(),
                    existing.expiresAt(),
                    existing.createdAt(),
                    updatedAt);
            grants.put(grantId, updated);
        }
    }

    @Override
    public void updateComponentState(
            UUID componentId, RewardComponentState state, @Nullable UUID journalOperationId, Instant updatedAt) {
        RewardGrantComponent comp = components.get(componentId);
        if (comp != null) {
            RewardGrantComponent updated = new RewardGrantComponent(
                    comp.componentId(),
                    comp.grantId(),
                    comp.componentIndex(),
                    comp.componentOperationId(),
                    comp.componentType(),
                    comp.payloadTypeId(),
                    comp.payloadSchemaVersion(),
                    comp.payloadData(),
                    state,
                    journalOperationId != null ? journalOperationId : comp.journalOperationId(),
                    updatedAt);
            components.put(componentId, updated);

            // Also refresh parent grant
            RewardGrant parent = grants.get(comp.grantId());
            if (parent != null) {
                grants.put(parent.grantId(), reconstruct(parent));
            }
        }
    }

    @Override
    public int expireGrants(Instant now) {
        int count = 0;
        for (RewardGrant g : grants.values()) {
            if (g.state() == RewardGrantState.PENDING && g.isExpired(now)) {
                updateGrantState(g.grantId(), RewardGrantState.EXPIRED, null, now);
                count++;
            }
        }
        return count;
    }

    private RewardGrant reconstruct(RewardGrant grant) {
        List<RewardGrantComponent> comps = new ArrayList<>();
        for (RewardGrantComponent c : grant.components()) {
            RewardGrantComponent current = components.get(c.componentId());
            comps.add(current != null ? current : c);
        }
        comps.sort(Comparator.comparingInt(RewardGrantComponent::componentIndex));
        return new RewardGrant(
                grant.grantId(),
                grant.recipientProfileId(),
                grant.sourceType(),
                grant.sourceId(),
                grant.state(),
                comps,
                grant.claimedAt(),
                grant.expiresAt(),
                grant.createdAt(),
                grant.updatedAt());
    }
}
