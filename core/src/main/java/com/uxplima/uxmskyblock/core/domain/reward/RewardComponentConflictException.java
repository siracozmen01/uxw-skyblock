package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Thrown when an invariant conflict is detected in reward components.
 */
public class RewardComponentConflictException extends RuntimeException {

    public RewardComponentConflictException(String message) {
        super(message);
    }
}
