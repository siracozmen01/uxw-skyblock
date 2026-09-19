package com.uxplima.uxmskyblock.core.application.gamemode;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain orchestration service managing game mode bindings and canonical root references.
 */
public final class GameModeHierarchyService {

    private final GameModeHierarchyStoragePort storagePort;

    public GameModeHierarchyService(GameModeHierarchyStoragePort storagePort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
    }

    public GameModeInstance getOrCreateSkyblockInstance(ProfileId profileId, String rulesetConfig) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(rulesetConfig, "rulesetConfig must not be null");

        return storagePort.findInstanceByProfileId(profileId).orElseGet(() -> {
            Instant now = Instant.now();
            GameModeInstance instance = GameModeInstance.create(
                    GameModeInstanceId.random(), profileId, GameModeType.SKYBLOCK, rulesetConfig, now);
            storagePort.saveGameModeInstance(instance);
            return instance;
        });
    }

    public void bindIsland(GameModeInstanceId instanceId, IslandId islandId) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(islandId, "islandId must not be null");

        PrimaryGameplayRootRef ref =
                PrimaryGameplayRootRef.forIsland(instanceId, islandId.value().toString(), Instant.now());
        storagePort.savePrimaryGameplayRootRef(ref);
    }

    public Optional<PrimaryGameplayRootRef> findRootRef(GameModeInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        return storagePort.findRootRefByInstanceId(instanceId);
    }

    public Optional<GameModeInstance> findInstanceByProfile(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return storagePort.findInstanceByProfileId(profileId);
    }
}
