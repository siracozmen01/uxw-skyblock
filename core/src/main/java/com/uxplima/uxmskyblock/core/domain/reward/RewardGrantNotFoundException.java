package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Thrown when a reward grant cannot be found by identifier.
 */
public class RewardGrantNotFoundException extends RuntimeException {

    public RewardGrantNotFoundException(String message) {
        super(message);
    }
}
