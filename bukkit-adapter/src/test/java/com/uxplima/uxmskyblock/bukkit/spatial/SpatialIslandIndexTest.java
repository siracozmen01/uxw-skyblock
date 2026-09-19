package com.uxplima.uxmskyblock.bukkit.spatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SpatialIslandIndexTest {

    private IslandStoragePort storagePort;
    private SchedulerPort schedulerPort;
    private SpatialIslandIndex index;

    @BeforeEach
    void setUp() {
        storagePort = mock(IslandStoragePort.class);
        schedulerPort = mock(SchedulerPort.class);
        index = new SpatialIslandIndex(storagePort, schedulerPort);
    }

    @Test
    @DisplayName("indexIsland maps all overlapping chunks to the island")
    void indexIslandMapsChunks() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 32);
        Island island = Island.create(
                islandId, bounds, new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), Instant.now());

        index.indexIsland(island, "skyblock_world");

        assertThat(index.totalIndexedIslands()).isEqualTo(1);
        assertThat(index.totalIndexedChunks()).isGreaterThan(1);

        Optional<Island> found = index.findIslandAt("skyblock_world", 10, 10);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(islandId);

        // Verification: storagePort was NOT called during cache hit
        verify(storagePort, never()).findIslandByLocation(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("findIslandAt returns empty for coordinates outside island bounds")
    void findIslandAtReturnsEmptyOutsideBounds() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 16);
        Island island = Island.create(
                islandId, bounds, new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), Instant.now());

        index.indexIsland(island, "skyblock_world");

        Optional<Island> found = index.findIslandAt("skyblock_world", 1000, 1000);
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("removeIsland purges all chunk mappings and island entry")
    void removeIslandPurgesChunks() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 32);
        Island island = Island.create(
                islandId, bounds, new PlayerUuid(UUID.randomUUID()), new ProfileId(UUID.randomUUID()), Instant.now());

        index.indexIsland(island, "skyblock_world");
        index.removeIsland(islandId);

        assertThat(index.totalIndexedIslands()).isZero();
        assertThat(index.totalIndexedChunks()).isZero();
        assertThat(index.findIslandAt("skyblock_world", 0, 0)).isEmpty();
    }
}
