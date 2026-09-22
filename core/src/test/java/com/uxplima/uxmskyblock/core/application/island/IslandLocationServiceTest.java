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
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
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
        IslandLocationService.SpawnUpdate updated =
                locationService.updateSpawn(profileId, "world", 10.5, 105.0, 20.5, 90.0f, 0.0f);
        assertThat(updated).isEqualTo(IslandLocationService.SpawnUpdate.UPDATED);

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
        IslandLocationService.SpawnUpdate updated =
                locationService.updateSpawn(new ProfileId(UUID.randomUUID()), "world", 0, 0, 0, 0, 0);
        assertThat(updated).isEqualTo(IslandLocationService.SpawnUpdate.NO_ISLAND);
    }

    @Test
    @DisplayName("A spawn outside the island's bounds is refused and the old spawn stays")
    void aSpawnOutsideTheBoundsIsRefused() {
        // The island runs from -50 to 50. Somebody else's island starts a little past that.
        assertThat(locationService.updateSpawn(profileId, "world", 320.5, 70.0, 0.5, 0.0f, 0.0f))
                .isEqualTo(IslandLocationService.SpawnUpdate.OUTSIDE_THE_ISLAND);
        assertThat(locationService.updateSpawn(profileId, "world", 0.5, 70.0, 50.9, 0.0f, 0.0f))
                .describedAs("the last block inside the bounds")
                .isEqualTo(IslandLocationService.SpawnUpdate.UPDATED);
        assertThat(locationService.updateSpawn(profileId, "world", 0.5, 70.0, 51.0, 0.0f, 0.0f))
                .describedAs("the first block past them")
                .isEqualTo(IslandLocationService.SpawnUpdate.OUTSIDE_THE_ISLAND);

        assertThat(locationService.resolveHome(profileId).orElseThrow().spawnZ())
                .isEqualTo(50.9);
    }

    @Test
    @DisplayName("A spawn in another world is refused, whatever its coordinates")
    void aSpawnInAnotherWorldIsRefused() {
        assertThat(locationService.updateSpawn(profileId, "world_nether", 1.5, 70.0, 1.5, 0.0f, 0.0f))
                .isEqualTo(IslandLocationService.SpawnUpdate.OUTSIDE_THE_ISLAND);
        assertThat(locationService.resolveHome(profileId).orElseThrow().worldName())
                .isEqualTo("world");
    }

    @Test
    @DisplayName("A member whose role cannot change settings cannot move the spawn")
    void aMemberWithoutTheRoleCannotMoveIt() {
        ProfileId mate = new ProfileId(UUID.randomUUID());
        Island island = java.util.Objects.requireNonNull(storage.islands.get(islandId));
        storage.saveIsland(
                island.addMember(
                        new IslandMember(new PlayerUuid(UUID.randomUUID()), mate, IslandRole.MEMBER, Instant.now())),
                initialLocation);
        storage.profileToIsland.put(mate, islandId);

        assertThat(locationService.updateSpawn(mate, "world", 5.5, 70.0, 5.5, 0.0f, 0.0f))
                .isEqualTo(IslandLocationService.SpawnUpdate.NOT_ALLOWED);
        assertThat(locationService.resolveHome(profileId).orElseThrow().spawnX())
                .isEqualTo(0.5);
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
