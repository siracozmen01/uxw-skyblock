package com.uxplima.uxmskyblock.core.application.reward;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.jspecify.annotations.Nullable;

/**
 * Outbound persistence port for saving, querying, and updating reward grants and components.
 */
public interface RewardStoragePort {

    /**
     * Persists a newly issued reward grant along with all child components within an atomic transaction.
     *
     * @param grant the reward grant aggregate
     */
    void saveGrant(RewardGrant grant);

    /**
     * Retrieves a reward grant by its unique ID.
     *
     * @param grantId grant ID
     * @return optional containing the grant if found
     */
    Optional<RewardGrant> findGrantById(RewardGrantId grantId);

    /**
     * Finds all pending or in-progress grants for a specific recipient profile.
     *
     * @param recipientProfileId recipient profile ID
     * @return list of matching grants
     */
    List<RewardGrant> findPendingGrantsByRecipient(ProfileId recipientProfileId);

    /**
     * Finds all grants regardless of state for a specific recipient profile.
     *
     * @param recipientProfileId recipient profile ID
     * @return list of matching grants
     */
    List<RewardGrant> findAllGrantsByRecipient(ProfileId recipientProfileId);

    /**
     * Atomically updates the state and claimed timestamp of a reward grant.
     *
     * @param grantId grant ID
     * @param state new grant state
     * @param claimedAt optional timestamp of completion
     * @param updatedAt timestamp of update
     */
    void updateGrantState(
            RewardGrantId grantId, RewardGrantState state, @Nullable Instant claimedAt, Instant updatedAt);

    /**
     * Atomically updates the delivery state and journal operation reference of an individual component.
     *
     * @param componentId component primary UUID
     * @param state new component delivery state
     * @param journalOperationId protocol journal / operation UUID
     * @param updatedAt timestamp of update
     */
    void updateComponentState(
            UUID componentId, RewardComponentState state, @Nullable UUID journalOperationId, Instant updatedAt);

    /**
     * Expires all pending grants whose expiration timestamp is at or before the given reference time.
     *
     * @param now current reference timestamp
     * @return count of newly expired grants
     */
    int expireGrants(Instant now);
}
