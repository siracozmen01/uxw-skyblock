package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Thrown when attempting to claim a reward grant that has already been claimed.
 */
public class RewardGrantAlreadyClaimedException extends RuntimeException {

    public RewardGrantAlreadyClaimedException(String message) {
        super(message);
    }
}
