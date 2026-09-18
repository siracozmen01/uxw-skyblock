package com.uxplima.uxmskyblock.core.application.reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Summary result of claiming all pending rewards for a player profile.
 *
 * @param totalProcessed total number of grants evaluated
 * @param successfullyClaimed count of grants fully claimed
 * @param failedOrIncomplete count of grants that failed or remain uncompleted
 * @param results individual results per grant
 */
public record ClaimAllRewardsResult(
        int totalProcessed, int successfullyClaimed, int failedOrIncomplete, List<ClaimRewardResult> results) {

    public ClaimAllRewardsResult {
        Objects.requireNonNull(results, "results must not be null");
        results = Collections.unmodifiableList(new ArrayList<>(results));
    }
}
