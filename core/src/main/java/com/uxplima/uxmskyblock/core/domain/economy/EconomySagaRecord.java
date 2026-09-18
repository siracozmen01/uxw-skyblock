package com.uxplima.uxmskyblock.core.domain.economy;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Immutable state record of an economy saga transaction for distributed consensus
 * and crash recovery.
 */
public record EconomySagaRecord(
        SagaId sagaId,
        PlayerUuid playerUuid,
        ProfileId profileId,
        IslandId islandId,
        SagaType sagaType,
        SagaState state,
        long amountMinorUnits,
        String currency,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public EconomySagaRecord {
        Objects.requireNonNull(sagaId, "sagaId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(sagaType, "sagaType must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("amountMinorUnits must be positive, got: " + amountMinorUnits);
        }
    }

    public static EconomySagaRecord start(
            SagaId sagaId,
            PlayerUuid playerUuid,
            ProfileId profileId,
            IslandId islandId,
            SagaType sagaType,
            long amountMinorUnits,
            String currency,
            Instant expiresAt,
            Instant now) {
        return new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                sagaType,
                SagaState.STARTED,
                amountMinorUnits,
                currency,
                expiresAt,
                now,
                now);
    }

    public EconomySagaRecord withState(SagaState newState, Instant now) {
        return new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                sagaType,
                newState,
                amountMinorUnits,
                currency,
                expiresAt,
                createdAt,
                now);
    }
}
