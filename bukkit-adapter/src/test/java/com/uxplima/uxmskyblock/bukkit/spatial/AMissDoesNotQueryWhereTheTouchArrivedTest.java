package com.uxplima.uxmskyblock.bukkit.spatial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * A block nobody has touched yet does not stop the region while the database answers.
 *
 * <p>This index says whose island a block belongs to, and every break, placement, interaction and
 * boundary crossing asks it. When it has not seen the chunk before it has to fill that in, and the
 * filling is a query. Given a scheduler it hands the query over and answers nothing for now, so the
 * touch is refused and the next one is answered from memory. Given none it ran the query where it
 * stood, which is the region thread.
 *
 * <p>The plugin built it without a scheduler, so the second shape was the one running.
 */
class AMissDoesNotQueryWhereTheTouchArrivedTest {

    private static Location somewhere(ServerMock server) {
        World world = server.addSimpleWorld("skyblock_world");
        return new Location(world, 5000, 64, 5000);
    }

    @Test
    @DisplayName("With a scheduler, a miss hands the query over instead of running it")
    void aMissIsHandedOver() {
        ServerMock server = MockBukkit.mock();
        try {
            IslandStoragePort storagePort = mock(IslandStoragePort.class);
            when(storagePort.findIslandByLocation(anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.empty());
            SchedulerPort scheduler = mock(SchedulerPort.class);

            SpatialIslandIndex index = new SpatialIslandIndex(storagePort, scheduler);
            Optional<?> answer = index.findIslandAt(somewhere(server));

            assertThat(answer).describedAs("what the touch is told, now").isEmpty();
            verify(storagePort, never()).findIslandByLocation(anyString(), anyInt(), anyInt());
            verify(scheduler).async(any(Runnable.class));
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("The query the scheduler was handed is the one that fills the miss")
    void theHandedOverQueryFillsTheMiss() {
        ServerMock server = MockBukkit.mock();
        try {
            IslandStoragePort storagePort = mock(IslandStoragePort.class);
            when(storagePort.findIslandByLocation(anyString(), anyInt(), anyInt()))
                    .thenReturn(Optional.empty());

            SpatialIslandIndex index = new SpatialIslandIndex(storagePort, new InlineSchedulerPort());
            index.findIslandAt(somewhere(server));

            verify(storagePort).findIslandByLocation(anyString(), anyInt(), anyInt());
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    @DisplayName("A chunk already indexed is answered without reaching storage at all")
    void ahitNeverReachesStorage() {
        ServerMock server = MockBukkit.mock();
        try {
            IslandStoragePort storagePort = mock(IslandStoragePort.class);
            SchedulerPort scheduler = mock(SchedulerPort.class);
            SpatialIslandIndex index = new SpatialIslandIndex(storagePort, scheduler);

            Location at = somewhere(server);
            com.uxplima.uxmskyblock.core.domain.island.Island island =
                    com.uxplima.uxmskyblock.core.domain.island.Island.create(
                            com.uxplima.uxmskyblock.core.domain.identity.IslandId.of(UUID.randomUUID()),
                            com.uxplima.uxmskyblock.core.domain.island.IslandBounds.fromCenterAndRadius(5000, 5000, 32),
                            new com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid(UUID.randomUUID()),
                            new com.uxplima.uxmskyblock.core.domain.identity.ProfileId(UUID.randomUUID()),
                            java.time.Instant.now());
            index.indexIsland(island, "skyblock_world");

            assertThat(index.findIslandAt(at)).isPresent();
            verify(storagePort, never()).findIslandByLocation(anyString(), anyInt(), anyInt());
            verify(scheduler, never()).async(any(Runnable.class));
        } finally {
            MockBukkit.unmock();
        }
    }
}
