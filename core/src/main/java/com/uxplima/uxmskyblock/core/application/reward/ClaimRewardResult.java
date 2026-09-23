package com.uxplima.uxmskyblock.core.application.reward;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.jspecify.annotations.Nullable;

/**
 * Result of claiming an individual {@link com.uxplima.uxmskyblock.core.domain.reward.RewardGrant}.
 *
 * @param success whether all components were durably committed and grant reached CLAIMED
 * @param grantId target grant ID
 * @param finalState final state of the grant following claim attempt
 * @param committedCount number of components successfully committed
 * @param totalCount total number of components in the grant
 * @param failureReason optional explanation if not fully successful, for the log and never for the player
 * @param refusal what kind of refusal this is, which is what the player is told
 */
public record ClaimRewardResult(
        boolean success,
        RewardGrantId grantId,
        RewardGrantState finalState,
        int committedCount,
        int totalCount,
        @Nullable String failureReason,
        Refusal refusal) {

    /** Why a claim did not complete, in the terms a player is told. */
    public enum Refusal {
        /** The claim completed. */
        NONE,
        /** Another claim of the same reward is under way. */
        ALREADY_BEING_CLAIMED,
        /** Some part of the reward could not be handed over, and it stays in the inbox. */
        UNDELIVERED
    }

    public ClaimRewardResult {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
        Objects.requireNonNull(refusal, "refusal must not be null");
    }

    /** A result without a refusal kind: a failure is taken as a reward that was not handed over. */
    public ClaimRewardResult(
            boolean success,
            RewardGrantId grantId,
            RewardGrantState finalState,
            int committedCount,
            int totalCount,
            @Nullable String failureReason) {
        this(
                success,
                grantId,
                finalState,
                committedCount,
                totalCount,
                failureReason,
                success ? Refusal.NONE : Refusal.UNDELIVERED);
    }

    public static ClaimRewardResult success(RewardGrantId grantId, int totalCount) {
        return new ClaimRewardResult(
                true, grantId, RewardGrantState.CLAIMED, totalCount, totalCount, null, Refusal.NONE);
    }

    public static ClaimRewardResult failure(
            RewardGrantId grantId,
            RewardGrantState finalState,
            int committedCount,
            int totalCount,
            String failureReason) {
        return new ClaimRewardResult(
                false,
                grantId,
                finalState,
                committedCount,
                totalCount,
                Objects.requireNonNull(failureReason, "failureReason must not be null"),
                Refusal.UNDELIVERED);
    }

    /** Another claim of this reward is under way, so this one did nothing. */
    public static ClaimRewardResult alreadyBeingClaimed(
            RewardGrantId grantId, RewardGrantState finalState, int totalCount) {
        return new ClaimRewardResult(
                false,
                grantId,
                finalState,
                0,
                totalCount,
                "This reward is already being claimed.",
                Refusal.ALREADY_BEING_CLAIMED);
    }

    public Optional<String> optFailureReason() {
        return Optional.ofNullable(failureReason);
    }
}
