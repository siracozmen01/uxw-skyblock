package com.uxplima.uxmskyblock.core.domain.reward;

/**
 * Thrown when delivery of a reward component fails across its designated protocol.
 */
public class RewardDeliveryException extends RuntimeException {

    public RewardDeliveryException(String message) {
        super(message);
    }

    public RewardDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
