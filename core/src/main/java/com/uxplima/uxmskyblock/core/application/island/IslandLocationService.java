package com.uxplima.uxmskyblock.core.application.island;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;

/**
 * Application service managing island spatial locations, homes, and spawn points.
 */
public final class IslandLocationService {

    private final IslandStoragePort islandStoragePort;

    public IslandLocationService(IslandStoragePort islandStoragePort) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
    }

    public Optional<IslandLocation> resolveHome(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId).flatMap(islandStoragePort::findLocationByIslandId);
    }

    public Optional<IslandId> findIslandId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId);
    }

    public Optional<IslandLocation> findLocation(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return islandStoragePort.findLocationByIslandId(islandId);
    }

    public boolean updateSpawn(
            ProfileId profileId, String worldName, double x, double y, double z, float yaw, float pitch) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return false;
        }

        IslandId islandId = optIslandId.get();
        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        Optional<IslandLocation> optLoc = islandStoragePort.findLocationByIslandId(islandId);
        if (optIsland.isEmpty() || optLoc.isEmpty()) {
            return false;
        }

        IslandLocation updatedLocation =
                new IslandLocation(islandId, worldName, optLoc.get().bounds(), x, y, z, yaw, pitch);

        islandStoragePort.saveIsland(optIsland.get(), updatedLocation);
        return true;
    }
}
