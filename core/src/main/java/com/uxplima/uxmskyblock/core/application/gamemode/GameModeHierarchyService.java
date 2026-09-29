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

    /** The mode each profile's instance plays, as last read or written here. */
    private final java.util.concurrent.ConcurrentMap<ProfileId, GameModeType> modes =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                modes.put(profileId, mode);
                return found;
            }
            GameModeInstance moved =
                    new GameModeInstance(found.id(), profileId, mode, rulesetConfig, found.createdAt(), Instant.now());
            storagePort.saveGameModeInstance(moved);
            modes.put(profileId, mode);
            return moved;
        }
        GameModeInstance instance =
                GameModeInstance.create(GameModeInstanceId.random(), profileId, mode, rulesetConfig, Instant.now());
        storagePort.saveGameModeInstance(instance);
        modes.put(profileId, mode);
        return instance;
    }

    /**
     * The mode a profile's island plays, read once and then kept. A profile with no instance yet plays
     * skyblock, the mode every island had before there were others.
     */
    public GameModeType modeOf(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        GameModeType held = modes.get(profileId);
        if (held != null) {
            return held;
        }
        GameModeType read = storagePort
                .findInstanceByProfileId(profileId)
                .map(GameModeInstance::gameModeType)
                .orElse(GameModeType.SKYBLOCK);
        modes.put(profileId, read);
        return read;
    }

    /** The mode if it has been read or written here, without reading it, for a region thread. */
    public Optional<GameModeType> knownModeOf(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return Optional.ofNullable(modes.get(profileId));
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

    /**
     * The gameplay root an island is, read and never written.
     *
     * <p>An island made before islands were bound into the hierarchy has no row of its own; it is the
     * root of its owner's instance all the same, so that is what it reads as.
     */
    public Optional<PrimaryGameplayRootRef> rootOfIsland(IslandId islandId, ProfileId ownerProfileId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(ownerProfileId, "ownerProfileId must not be null");
        String rootId = islandId.value().toString();
        return storagePort
                .findRootRefByRootId(rootId, "ISLAND")
                .or(() -> storagePort
                        .findInstanceByProfileId(ownerProfileId)
                        .map(instance ->
                                PrimaryGameplayRootRef.forIsland(instance.id(), rootId, instance.createdAt())));
    }

    public Optional<GameModeInstance> findInstanceByProfile(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return storagePort.findInstanceByProfileId(profileId);
    }
}
