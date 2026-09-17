package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandLocationServiceTest {

    private FakeIslandStorage storage;
    private IslandLocationService locationService;
    private ProfileId profileId;
    private IslandId islandId;
    private IslandLocation initialLocation;

    @BeforeEach
    void setUp() {
        storage = new FakeIslandStorage();
        locationService = new IslandLocationService(storage);

        profileId = new ProfileId(UUID.randomUUID());
        islandId = IslandId.of(UUID.randomUUID());
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);

        Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());
        initialLocation = new IslandLocation(islandId, "world", bounds, 0.5, 100.0, 0.5, 0.0f, 0.0f);

        storage.saveIsland(island, initialLocation);
    }

    @Test
    @DisplayName("resolveHome returns saved location for profile")
    void resolveHomeReturnsLocation() {
        Optional<IslandLocation> loc = locationService.resolveHome(profileId);
        assertThat(loc).isPresent();
        assertThat(loc.get().worldName()).isEqualTo("world");
        assertThat(loc.get().spawnX()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("updateSpawn updates coordinates and saves to storage")
    void updateSpawnUpdatesCoordinates() {
        boolean updated = locationService.updateSpawn(profileId, "world", 10.5, 105.0, 20.5, 90.0f, 0.0f);
        assertThat(updated).isTrue();

        Optional<IslandLocation> loc = locationService.resolveHome(profileId);
        assertThat(loc).isPresent();
        assertThat(loc.get().spawnX()).isEqualTo(10.5);
        assertThat(loc.get().spawnY()).isEqualTo(105.0);
        assertThat(loc.get().spawnZ()).isEqualTo(20.5);
        assertThat(loc.get().spawnYaw()).isEqualTo(90.0f);
    }

    @Test
    @DisplayName("updateSpawn returns false when profile has no island")
    void updateSpawnReturnsFalseWhenNoIsland() {
        boolean updated = locationService.updateSpawn(new ProfileId(UUID.randomUUID()), "world", 0, 0, 0, 0, 0);
        assertThat(updated).isFalse();
    }

    private static class FakeIslandStorage implements IslandStoragePort {
        final Map<IslandId, Island> islands = new HashMap<>();
        final Map<ProfileId, IslandId> profileToIsland = new HashMap<>();
        final Map<IslandId, IslandLocation> locations = new HashMap<>();

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            islands.put(island.id(), island);
            profileToIsland.put(island.ownerProfileId(), island.id());
            locations.put(island.id(), location);
        }

        @Override
        public Optional<Island> findIslandById(IslandId islandId) {
            return Optional.ofNullable(islands.get(islandId));
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return Optional.ofNullable(profileToIsland.get(profileId));
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId islandId) {
            return Optional.ofNullable(locations.get(islandId));
        }

        @Override
        public void deleteIsland(IslandId islandId) {
            islands.remove(islandId);
            locations.remove(islandId);
        }
    }
}
