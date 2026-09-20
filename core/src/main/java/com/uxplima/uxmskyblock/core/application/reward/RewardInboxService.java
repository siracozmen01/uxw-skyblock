package com.uxplima.uxmskyblock.core.application.reward;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantNotFoundException;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise application service governing the issuance, inspection, expiration,
 * and multi-protocol claiming of offline reward grants.
 */
public final class RewardInboxService {

    private final RewardStoragePort storagePort;
    private final RewardClaimCoordinator claimCoordinator;

    public RewardInboxService(RewardStoragePort storagePort, RewardClaimCoordinator claimCoordinator) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.claimCoordinator = Objects.requireNonNull(claimCoordinator, "claimCoordinator must not be null");
    }

    /**
     * Issues a new durable reward grant to a recipient profile.
     *
     * @param recipientProfileId recipient profile ID
     * @param sourceType source category (e.g. "SEASON_PAYOUT", "ADMIN_GRANT")
     * @param sourceId external or business source ID
     * @param expiresAt optional expiration timestamp
     * @param draftComponents list of components to deliver
     * @return persisted reward grant aggregate
     */
    public RewardGrant issueReward(
            ProfileId recipientProfileId,
            String sourceType,
            String sourceId,
            @Nullable Instant expiresAt,
            List<RewardDraftComponent> draftComponents) {
        return issueReward(
                RewardGrantId.random(), recipientProfileId, sourceType, sourceId, expiresAt, draftComponents);
    }

    /**
     * Issues a new durable reward grant to a recipient profile with an explicit grant ID.
     * If a grant with this ID already exists, it is returned idempotently without re-saving.
     *
     * @param grantId explicit grant ID
     * @param recipientProfileId recipient profile ID
     * @param sourceType source category (e.g. "SEASON_PAYOUT", "ADMIN_GRANT")
     * @param sourceId external or business source ID
     * @param expiresAt optional expiration timestamp
     * @param draftComponents list of components to deliver
     * @return persisted reward grant aggregate
     */
    public RewardGrant issueReward(
            RewardGrantId grantId,
            ProfileId recipientProfileId,
            String sourceType,
            String sourceId,
            @Nullable Instant expiresAt,
            List<RewardDraftComponent> draftComponents) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(draftComponents, "draftComponents must not be null");

        Optional<RewardGrant> existing = storagePort.findGrantById(grantId);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (draftComponents.isEmpty()) {
            throw new IllegalArgumentException("Cannot issue a reward grant with zero components");
        }

        Instant now = Instant.now();

        List<RewardGrantComponent> components = new ArrayList<>(draftComponents.size());
        for (int i = 0; i < draftComponents.size(); i++) {
            RewardDraftComponent draft = draftComponents.get(i);
            RewardComponentOperationId opId = RewardComponentOperationId.derive(grantId, i);
            RewardGrantComponent comp = new RewardGrantComponent(
                    UUID.randomUUID(),
                    grantId,
                    i,
                    opId,
                    draft.componentType(),
                    draft.payloadTypeId(),
                    draft.payloadSchemaVersion(),
                    draft.payloadData(),
                    RewardComponentState.PENDING,
                    null,
                    now);
            components.add(comp);
        }

        RewardGrant grant = new RewardGrant(
                grantId,
                recipientProfileId,
                sourceType,
                sourceId,
                RewardGrantState.PENDING,
                components,
                null,
                expiresAt,
                now,
                now);

        storagePort.saveGrant(grant);
        return grant;
    }

    /**
     * Retrieves all pending reward grants awaiting claim for a profile.
     */
    public List<RewardGrant> getPendingRewards(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        return storagePort.findPendingGrantsByRecipient(recipientProfileId);
    }

    /**
     * Retrieves all reward grants in any state for a profile.
     */
    public List<RewardGrant> getAllRewards(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");
        return storagePort.findAllGrantsByRecipient(recipientProfileId);
    }

    /**
     * Claims a specific reward grant by ID.
     */
    public ClaimRewardResult claimReward(RewardGrantId grantId, ProfileId callerProfileId) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(callerProfileId, "callerProfileId must not be null");

        RewardGrant grant = storagePort
                .findGrantById(grantId)
                .orElseThrow(() -> new RewardGrantNotFoundException("Reward grant not found: " + grantId));

        return claimCoordinator.coordinateClaim(grant, callerProfileId);
    }

    /**
     * Claims all pending reward grants for a profile.
     */
    public ClaimAllRewardsResult claimAllRewards(ProfileId callerProfileId) {
        Objects.requireNonNull(callerProfileId, "callerProfileId must not be null");
        List<RewardGrant> pending = storagePort.findPendingGrantsByRecipient(callerProfileId);

        int claimed = 0;
        int failed = 0;
        List<ClaimRewardResult> results = new ArrayList<>(pending.size());

        for (RewardGrant grant : pending) {
            ClaimRewardResult res = claimCoordinator.coordinateClaim(grant, callerProfileId);
            results.add(res);
            if (res.success()) {
                claimed++;
            } else {
                failed++;
            }
        }

        return new ClaimAllRewardsResult(pending.size(), claimed, failed, results);
    }

    /**
     * Purges/marks expired grants whose expiration timestamp has passed.
     */
    public int expirePendingRewards(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return storagePort.expireGrants(now);
    }
}
