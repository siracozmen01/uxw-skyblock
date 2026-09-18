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
 * @param failureReason optional explanation if not fully successful
 */
public record ClaimRewardResult(
        boolean success,
        RewardGrantId grantId,
        RewardGrantState finalState,
        int committedCount,
        int totalCount,
        @Nullable String failureReason) {

    public ClaimRewardResult {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
    }

    public static ClaimRewardResult success(RewardGrantId grantId, int totalCount) {
        return new ClaimRewardResult(true, grantId, RewardGrantState.CLAIMED, totalCount, totalCount, null);
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
                Objects.requireNonNull(failureReason, "failureReason must not be null"));
    }

    public Optional<String> optFailureReason() {
        return Optional.ofNullable(failureReason);
    }
}
