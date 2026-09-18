package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Thrown when attempting to claim an expired reward grant.
 */
public class RewardGrantExpiredException extends RuntimeException {

    public RewardGrantExpiredException(String message) {
        super(message);
    }
}
