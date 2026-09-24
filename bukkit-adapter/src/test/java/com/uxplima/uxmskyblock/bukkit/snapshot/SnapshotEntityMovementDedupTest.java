package com.uxplima.uxmskyblock.bukkit.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Cow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * A creature that walks across a chunk edge while the island is captured is written and brought back
 * once.
 *
 * <p>The testing standard names this test. Each region's chunks are read on their own, so a cow read
 * in one chunk and then walking into the next is read again there. A snapshot used to hold no
 * creatures at all, so a restore brought back none; it now keeps them by id and brings each back once,
 * and a restore that runs again brings back no second one.
 */
class SnapshotEntityMovementDedupTest {

    private final UUID island = UUID.randomUUID();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @BeforeEach
    void setUp() {
        world = MockBukkit.mock().addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName(
            "A cow that crosses a chunk edge during the capture is written once, and the restore brings back exactly one")
    void aCrossingCowComesBackOnce() {
        Cow cow = (Cow) world.spawnEntity(new Location(world, -3.5, 64, -3.5), EntityType.COW);
        UUID original = cow.getUniqueId();
        // Chunk -1,-1 is read first. As soon as it has been, the cow walks into chunk 0,0, read last.
        WorldDimensionSnapshotAdapter capturing = adapter((region, task) -> {
            task.run();
            if (region.equals("-1,-1")) {
                cow.teleport(new Location(world, 3.5, 64, 3.5));
            }
        });

        byte[] payload = capturing.captureWorldDimension(rootRef(), DimensionId.OVERWORLD);
        cow.remove();

        WorldDimensionSnapshotAdapter restoring = adapter((region, task) -> task.run());
        restoring.restoreEntities(rootRef(), DimensionId.OVERWORLD, payload);

        assertThat(world.getEntitiesByClass(Cow.class))
                .singleElement()
                .satisfies(back -> assertThat(back.getPersistentDataContainer()
                                .get(SnapshotEntities.ORIGINAL, org.bukkit.persistence.PersistentDataType.STRING))
                        .isEqualTo(original.toString()));
    }

    @Test
    @DisplayName("A restore that runs again, as a replayed unit does, brings back no second creature")
    void aReplayBringsBackNone() {
        world.spawnEntity(new Location(world, 2.5, 64, 2.5), EntityType.COW).getUniqueId();
        WorldDimensionSnapshotAdapter adapter = adapter((region, task) -> task.run());
        byte[] payload = adapter.captureWorldDimension(rootRef(), DimensionId.OVERWORLD);
        world.getEntitiesByClass(Cow.class).forEach(Cow::remove);

        adapter.restoreEntities(rootRef(), DimensionId.OVERWORLD, payload);
        adapter.restoreEntities(rootRef(), DimensionId.OVERWORLD, payload);

        assertThat(world.getEntitiesByClass(Cow.class)).hasSize(1);
    }

    @Test
    @DisplayName("A creature still alive is not brought back beside itself")
    void aLivingCreatureIsLeftAlone() {
        world.spawnEntity(new Location(world, 1.5, 64, 1.5), EntityType.COW);
        WorldDimensionSnapshotAdapter adapter = adapter((region, task) -> task.run());
        byte[] payload = adapter.captureWorldDimension(rootRef(), DimensionId.OVERWORLD);

        adapter.restoreEntities(rootRef(), DimensionId.OVERWORLD, payload);

        assertThat(world.getEntitiesByClass(Cow.class)).hasSize(1);
    }

    @Test
    @DisplayName("A creature wearing an item is economic item state and is never brought back")
    void anEquippedCreatureIsNotTaken() {
        Zombie zombie = (Zombie) world.spawnEntity(new Location(world, 1.5, 64, 1.5), EntityType.ZOMBIE);
        zombie.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        WorldDimensionSnapshotAdapter adapter = adapter((region, task) -> task.run());
        byte[] payload = adapter.captureWorldDimension(rootRef(), DimensionId.OVERWORLD);
        zombie.remove();

        adapter.restoreEntities(rootRef(), DimensionId.OVERWORLD, payload);

        assertThat(world.getEntitiesByClass(Zombie.class)).isEmpty();
    }

    /** An adapter over an island around the origin, two chunks wide, whose regions run as told. */
    private WorldDimensionSnapshotAdapter adapter(java.util.function.BiConsumer<String, Runnable> regions) {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.accept(
                            call.getArgument(1, Integer.class) + "," + call.getArgument(2, Integer.class),
                            call.getArgument(3, Runnable.class));
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        IslandStoragePort islands = mock(IslandStoragePort.class);
        when(islands.findLocationByIslandId(any()))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(IslandId.of(island), "world", 0, 0, 4)));
        return new WorldDimensionSnapshotAdapter(mock(Plugin.class), islands, scheduler, null);
    }

    private PrimaryGameplayRootRef rootRef() {
        return new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), island.toString(), "ISLAND", Instant.now());
    }
}
