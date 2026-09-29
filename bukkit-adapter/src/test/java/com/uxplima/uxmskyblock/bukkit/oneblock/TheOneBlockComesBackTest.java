package com.uxplima.uxmskyblock.bukkit.oneblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Cow;
import org.bukkit.event.block.BlockBreakEvent;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockProgressPort;
import com.uxplima.uxmskyblock.core.application.oneblock.OneBlockService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhase;
import com.uxplima.uxmskyblock.core.domain.oneblock.OneBlockPhases;
import com.uxplima.uxmskyblock.core.domain.oneblock.WeightedPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A OneBlock island's block comes back after it is broken, as its phase draws it, and nothing else on
 * the island does.
 */
class TheOneBlockComesBackTest extends MockBukkitHarness {

    private final List<Runnable> regionTasks = new ArrayList<>();
    private final Map<IslandId, OneBlockProgressPort.OneBlockIsland> stored = new ConcurrentHashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private OneBlockListener listener;

    @SuppressWarnings("NullAway.Init")
    private OneBlockService service;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        player = createPlayer("Breaker");
        PlayerUuid owner = new PlayerUuid(player.getUniqueId());
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                owner,
                new ProfileId(player.getUniqueId()),
                Instant.now());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island);

        OneBlockPhases phases = new OneBlockPhases(
                List.of(
                        new OneBlockPhase(
                                "plains",
                                2,
                                new WeightedPool(Map.of("GRASS_BLOCK", 1.0)),
                                new WeightedPool(Map.of("COW", 1.0)),
                                1.0),
                        new OneBlockPhase(
                                "underground", 5, new WeightedPool(Map.of("IRON_ORE", 1.0)), WeightedPool.empty(), 0)),
                OneBlockPhases.AfterTheLast.STAY);
        service = new OneBlockService(memory(), phases, new SplittableRandom(3));
        service.start(island.id(), 0, 100, 0);

        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> regionTasks.add(call.getArgument(3, Runnable.class)))
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        listener = new OneBlockListener(
                service,
                islands,
                scheduler,
                Messages.bundled(),
                InteractionEffects.none(),
                new InteractionEffectPlayer(null, Messages.bundled()));
    }

    @Test
    @DisplayName("The broken block comes back as its phase draws it, with the creature the phase brings")
    void theBlockComesBack() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.GRASS_BLOCK);

        breakIt(block);

        assertThat(block.getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getEntitiesByClass(Cow.class)).hasSize(1);
        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isEqualTo(1);
    }

    @Test
    @DisplayName("The break that starts a phase tells the player the phase by its title, and draws from it")
    void aNewPhaseIsAnnounced() {
        Block block = world.getBlockAt(0, 100, 0);
        block.setType(Material.GRASS_BLOCK);

        breakIt(block);
        while (player.nextComponentMessage() != null) {
            // What the first break said, if anything, is not what this test is about.
        }
        breakIt(block);

        assertThat(block.getType()).isEqualTo(Material.IRON_ORE);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage())))
                .contains("Underground");
    }

    @Test
    @DisplayName("Any other block on the island breaks as usual, and is neither counted nor put back")
    void anyOtherBlockIsLeftAlone() {
        Block other = world.getBlockAt(3, 100, 3);
        other.setType(Material.STONE);

        breakIt(other);

        assertThat(other.getType()).isEqualTo(Material.AIR);
        assertThat(regionTasks).isEmpty();
        assertThat(service.island(island.id()).orElseThrow().blocksBroken()).isZero();
    }

    /** A break as the server carries it out: the event, then the block gone, then the region's turn. */
    private void breakIt(Block block) {
        listener.onBlockBreak(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
        List<Runnable> due = new ArrayList<>(regionTasks);
        regionTasks.clear();
        due.forEach(Runnable::run);
    }

    private OneBlockProgressPort memory() {
        return new OneBlockProgressPort() {
            @Override
            public void start(IslandId islandId, int x, int y, int z) {
                stored.put(islandId, new OneBlockIsland(islandId, x, y, z, 0));
            }

            @Override
            public List<OneBlockIsland> findAll() {
                return List.copyOf(stored.values());
            }

            @Override
            public Optional<OneBlockIsland> find(IslandId islandId) {
                return Optional.ofNullable(stored.get(islandId));
            }

            @Override
            public void addBreaks(IslandId islandId, long breaks) {}
        };
    }
}
