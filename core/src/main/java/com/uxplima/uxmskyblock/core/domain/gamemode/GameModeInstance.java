package com.uxplima.uxmskyblock.core.domain.gamemode;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain entity binding a specific player profile to a game mode instance with its ruleset configuration.
 */
public record GameModeInstance(
        GameModeInstanceId id,
        ProfileId profileId,
        GameModeType gameModeType,
        String rulesetConfig,
        Instant createdAt,
        Instant updatedAt) {

    public GameModeInstance {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(gameModeType, "gameModeType must not be null");
        Objects.requireNonNull(rulesetConfig, "rulesetConfig must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static GameModeInstance create(
            GameModeInstanceId id,
            ProfileId profileId,
            GameModeType gameModeType,
            String rulesetConfig,
            Instant now) {
        return new GameModeInstance(id, profileId, gameModeType, rulesetConfig, now, now);
    }
}
