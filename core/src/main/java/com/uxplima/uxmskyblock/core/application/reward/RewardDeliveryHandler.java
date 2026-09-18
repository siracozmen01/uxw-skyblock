package com.uxplima.uxmskyblock.core.application.reward;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import org.jspecify.annotations.Nullable;

/**
 * Protocol handler responsible for delivering a specific {@link RewardComponentType} to its destination.
 */
public interface RewardDeliveryHandler {

    /**
     * The component type supported by this handler.
     */
    RewardComponentType supportedType();

    /**
     * Executes delivery of the reward component to the recipient profile.
     *
     * @param grant parent grant context
     * @param component component to deliver
     * @param recipientProfileId recipient profile ID
     * @return delivery result
     */
    DeliveryResult deliver(RewardGrant grant, RewardGrantComponent component, ProfileId recipientProfileId);

    /**
     * Result of an attempted delivery.
     *
     * @param success whether delivery succeeded
     * @param journalOperationId optional protocol journal or saga operation ID
     * @param errorMessage optional error message upon failure
     */
    record DeliveryResult(
            boolean success,
            @Nullable UUID journalOperationId,
            @Nullable String errorMessage) {

        public DeliveryResult {
            if (success && errorMessage != null) {
                throw new IllegalArgumentException("Successful delivery cannot have an error message");
            }
        }

        public static DeliveryResult success(@Nullable UUID journalOperationId) {
            return new DeliveryResult(true, journalOperationId, null);
        }

        public static DeliveryResult failure(String errorMessage) {
            return new DeliveryResult(
                    false, null, Objects.requireNonNull(errorMessage, "errorMessage must not be null"));
        }

        public Optional<UUID> optJournalOperationId() {
            return Optional.ofNullable(journalOperationId);
        }
    }
}
