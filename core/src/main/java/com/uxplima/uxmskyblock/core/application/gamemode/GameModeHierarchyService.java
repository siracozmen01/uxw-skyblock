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
        return getOrCreateInstance(profileId, GameModeType.SKYBLOCK, rulesetConfig);
    }

    /**
     * The profile's game mode instance, playing {@code mode}.
     *
     * <p>A profile keeps one instance. One that deleted a skyblock island and started a OneBlock island
     * plays OneBlock now, so an instance found playing another mode is recorded as playing this one.
     */
    public GameModeInstance getOrCreateInstance(ProfileId profileId, GameModeType mode, String rulesetConfig) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(rulesetConfig, "rulesetConfig must not be null");

        Optional<GameModeInstance> existing = storagePort.findInstanceByProfileId(profileId);
        if (existing.isPresent()) {
            GameModeInstance found = existing.get();
            if (found.gameModeType() == mode) {
                return found;
            }
            GameModeInstance moved =
                    new GameModeInstance(found.id(), profileId, mode, rulesetConfig, found.createdAt(), Instant.now());
            storagePort.saveGameModeInstance(moved);
            return moved;
        }
        GameModeInstance instance =
                GameModeInstance.create(GameModeInstanceId.random(), profileId, mode, rulesetConfig, Instant.now());
        storagePort.saveGameModeInstance(instance);
        return instance;
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
