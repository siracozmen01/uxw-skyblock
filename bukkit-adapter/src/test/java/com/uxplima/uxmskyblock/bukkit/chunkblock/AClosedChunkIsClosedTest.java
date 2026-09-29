package com.uxplima.uxmskyblock.bukkit.chunkblock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkUnlockRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Nobody walks, builds or breaks in a closed chunk of a ChunkBlock island, an island of another mode is
 * left alone, the operator's node walks through, and whoever stands in a chunk as it closes is moved to
 * the island's spawn.
 */
class AClosedChunkIsClosedTest extends MockBukkitHarness {

    private static final String BYPASS = "server.chunks.bypass";
    private static final int Y = 100;

    private final Map<IslandId, ChunkPos> origins = new HashMap<>();
    private final Map<IslandId, List<ChunkPos>> opened = new HashMap<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private Island island;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockService service;

    @SuppressWarnings("NullAway.Init")
    private ChunkBlockListener listener;

    @BeforeEach
    void setUpIsland() {
        world = server.addSimpleWorld("skyblock");
        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        islands.cacheIsland(island, "skyblock");
        service = new ChunkBlockService(memory(), new ChunkUnlockRules(List.of(1L, 4L), 2));
        service.start(island.id(), 8, 8);
        service.unlock(island.id(), new ChunkPos(1, 0), island.bounds(), 1);

        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        IslandLocation home = new IslandLocation(island.id(), "skyblock", island.bounds(), 8.5, 101.0, 8.5, 0.0f, 0.0f);
        listener = new ChunkBlockListener(
                service, islands, scheduler, Messages.bundled(), id -> Optional.of(home), BYPASS);
    }

    @Test
    @DisplayName("A block is placed in an open chunk and refused in a closed one, with the level the next needs")
    void placing() {
        PlayerMock player = createPlayer("Builder");

        BlockPlaceEvent open = place(player, 20, 3);
        BlockPlaceEvent closed = place(player, 3, 20);

        assertThat(open.isCancelled()).isFalse();
        assertThat(closed.isCancelled()).isTrue();
        assertThat(said(player))
                .anySatisfy(line -> assertThat(line).contains("closed").contains("4"));
    }

    @Test
    @DisplayName("A block in a closed chunk is not broken")
    void breaking() {
        PlayerMock player = createPlayer("Breaker");
        Block block = world.getBlockAt(3, Y, 20);
        block.setType(Material.STONE);

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBreak(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("A step across into a closed chunk is refused; a step inside a chunk is never asked about")
    void walking() {
        PlayerMock player = createPlayer("Walker");

        PlayerMoveEvent intoClosed = move(player, 8, 15.5, 8, 16.5);
        PlayerMoveEvent intoOpen = move(player, 15.5, 8, 16.5, 8);
        PlayerMoveEvent withinOne = move(player, 1, 1, 2, 2);

        assertThat(intoClosed.isCancelled()).isTrue();
        assertThat(intoOpen.isCancelled()).isFalse();
        assertThat(withinOne.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("The operator's node walks and builds in a closed chunk")
    void theBypassNode() {
        PlayerMock staff = createPlayer("Staff");
        staff.addAttachment(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), BYPASS, true);

        assertThat(move(staff, 8, 15.5, 8, 16.5).isCancelled()).isFalse();
        assertThat(place(staff, 3, 20).isCancelled()).isFalse();
    }

    @Test
    @DisplayName("An island of another mode has no closed chunks")
    void anotherModeIsLeftAlone() {
        service.forget(island.id());
        PlayerMock player = createPlayer("Classic");

        assertThat(place(player, 3, 20).isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Whoever stands in a chunk as it closes is moved to the spawn; whoever stands in an open one stays")
    void closingMovesPlayersOut() {
        Stander inClosing = new Stander(server, new Location(world, 20, Y, 3));
        Stander inOpen = new Stander(server, new Location(world, 3, Y, 3));
        server.addPlayer(inClosing);
        server.addPlayer(inOpen);
        service.whenClosed(listener::moveOut);

        assertThat(service.onLevel(island.id(), 0)).containsExactly(new ChunkPos(1, 0));

        assertThat(inClosing.getLocation().getX()).isEqualTo(8.5);
        assertThat(inClosing.getLocation().getY()).isEqualTo(101.0);
        assertThat(said(inClosing)).anySatisfy(line -> assertThat(line).contains("moved"));
        assertThat(inOpen.getLocation().getX()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("A player found in a closed chunk, as Folia's teleports leave one, is moved out at the first step")
    void aPlayerFoundInsideIsMovedOut() {
        Stander arrived = new Stander(server, new Location(world, 3, Y, 20));
        server.addPlayer(arrived);

        PlayerMoveEvent step =
                new PlayerMoveEvent(arrived, new Location(world, 3, Y, 20), new Location(world, 3.5, Y, 20.5));
        listener.onMove(step);

        assertThat(step.isCancelled()).isTrue();
        assertThat(arrived.getLocation().getX()).isEqualTo(8.5);
        assertThat(said(arrived)).anySatisfy(line -> assertThat(line).contains("moved to the island spawn"));
    }

    private BlockPlaceEvent place(PlayerMock player, int x, int z) {
        Block block = world.getBlockAt(x, Y, z);
        BlockPlaceEvent event = new BlockPlaceEvent(
                block,
                block.getState(),
                world.getBlockAt(x, Y - 1, z),
                new ItemStack(Material.STONE),
                player,
                true,
                EquipmentSlot.HAND);
        listener.onPlace(event);
        return event;
    }

    private PlayerMoveEvent move(PlayerMock player, double fromX, double fromZ, double toX, double toZ) {
        PlayerMoveEvent event =
                new PlayerMoveEvent(player, new Location(world, fromX, Y, fromZ), new Location(world, toX, Y, toZ));
        listener.onMove(event);
        return event;
    }

    private static List<String> said(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            lines.add(line);
        }
        return lines;
    }

    /** A player standing somewhere, whom a teleport really moves. */
    private static final class Stander extends PlayerMock {

        Stander(org.mockbukkit.mockbukkit.ServerMock server, Location at) {
            super(server, "Stander" + UUID.randomUUID().toString().substring(0, 4), UUID.randomUUID());
            setLocation(at);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> teleportAsync(
                Location location,
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause,
                io.papermc.paper.entity.TeleportFlag... flags) {
            setLocation(location);
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }
    }

    private ChunkTerritoryPort memory() {
        return new ChunkTerritoryPort() {
            @Override
            public Map<IslandId, ChunkTerritory> findAll() {
                return Map.of();
            }

            @Override
            public Optional<ChunkTerritory> find(IslandId islandId) {
                ChunkPos origin = origins.get(islandId);
                return origin == null
                        ? Optional.empty()
                        : Optional.of(new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of())));
            }

            @Override
            public void start(IslandId islandId, ChunkPos origin) {
                origins.putIfAbsent(islandId, origin);
            }

            @Override
            public boolean open(IslandId islandId, ChunkPos chunk, int order) {
                return opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
            }

            @Override
            public void close(IslandId islandId, List<ChunkPos> chunks) {
                opened.getOrDefault(islandId, new ArrayList<>()).removeAll(chunks);
            }
        };
    }
}
