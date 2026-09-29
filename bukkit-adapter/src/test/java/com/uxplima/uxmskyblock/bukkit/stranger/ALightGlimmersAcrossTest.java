package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A light set on a StrangerRealms island's land glimmers at the same place in the Upside Down, and one
 * set in the Upside Down glimmers on the land, as light without a block. It goes out with the light,
 * never takes the place of a block, and happens nowhere else.
 */
class ALightGlimmersAcrossTest extends MockBukkitHarness {

    private static final String UPSIDE_DOWN = "skyblock_nether";
    private static final int Y = 70;

    private final Set<IslandId> stranger = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World land;

    @SuppressWarnings("NullAway.Init")
    private World upsideDown;

    @SuppressWarnings("NullAway.Init")
    private World elsewhere;

    @SuppressWarnings("NullAway.Init")
    private Glimmer glimmer;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @BeforeEach
    void setUpRealms() {
        land = server.addSimpleWorld("realms");
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        elsewhere = server.addSimpleWorld("elsewhere");
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        Island strange = island(0);
        islands.cacheIsland(strange, "realms");
        islands.cacheIsland(island(1000), "realms");
        // A StrangerRealms island in a world the operator does not make islands in: nothing glimmers there.
        Island stray = island(0);
        islands.cacheIsland(stray, "elsewhere");
        stranger.add(strange.id());
        stranger.add(stray.id());
        StrangerRealmsService service = new StrangerRealmsService(new StrangerRealmsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(stranger);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return stranger.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                stranger.add(islandId);
            }

            @Override
            public int farthestReach() {
                return 0;
            }
        });
        service.prime();
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        glimmer = new Glimmer(
                new Realms(service, islands, () -> UPSIDE_DOWN, () -> List.of("realms")),
                scheduler,
                new StrangerRealmsConfiguration.Glimmer(true, List.of("TORCH", "*_CANDLE", "LANTERN", "A*B*"), 9));
        player = createPlayer("Lamplighter");
    }

    @Test
    @DisplayName("A torch on the land glimmers in the Upside Down, and breaking it puts the glimmer out")
    void theLandGlimmersBelow() {
        Block torch = place(land, 8, Material.TORCH);

        Block there = upsideDown.getBlockAt(8, Y, 8);
        assertThat(there.getType()).isEqualTo(Material.LIGHT);
        if (there.getBlockData() instanceof Levelled levelled) {
            assertThat(levelled.getLevel()).isEqualTo(9);
        }

        glimmer.onDark(new BlockBreakEvent(torch, player));
        assertThat(there.getType()).isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("A candle in the Upside Down glimmers on the land, over the same place")
    void theUpsideDownGlimmersAbove() {
        place(upsideDown, 8, Material.RED_CANDLE);

        assertThat(land.getBlockAt(8, Y, 8).getType()).isEqualTo(Material.LIGHT);
    }

    @Test
    @DisplayName("A glimmer never takes a block's place, and a break never takes a block that is no glimmer")
    void blocksAreKept() {
        upsideDown.getBlockAt(8, Y, 8).setType(Material.STONE);
        Block torch = place(land, 8, Material.TORCH);
        assertThat(upsideDown.getBlockAt(8, Y, 8).getType()).isEqualTo(Material.STONE);

        glimmer.onDark(new BlockBreakEvent(torch, player));
        assertThat(upsideDown.getBlockAt(8, Y, 8).getType()).isEqualTo(Material.STONE);
    }

    @Test
    @DisplayName("No light, another mode's land, a world islands are not made in and no island glimmer nowhere")
    void elsewhereStaysDark() {
        place(land, 8, Material.STONE);
        place(land, 1008, Material.TORCH);
        place(elsewhere, 8, Material.TORCH);
        place(upsideDown, 50_000, Material.LANTERN);
        place(land, 50_000, Material.LANTERN);

        assertThat(upsideDown.getBlockAt(8, Y, 8).getType()).isEqualTo(Material.AIR);
        assertThat(upsideDown.getBlockAt(1008, Y, 8).getType()).isEqualTo(Material.AIR);
        assertThat(land.getBlockAt(50_000, Y, 8).getType()).isEqualTo(Material.LANTERN);
        assertThat(upsideDown.getBlockAt(50_000, Y, 8).getType()).isEqualTo(Material.LANTERN);
    }

    @Test
    @DisplayName("A level outside 1 to 15 falls back to the shipped glimmer")
    void theLevelIsBounded() throws Exception {
        StrangerRealmsConfiguration odd = StrangerRealmsConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString("glimmer { level = 0 }"));

        assertThat(odd.glimmer()).isEqualTo(StrangerRealmsConfiguration.Glimmer.SHIPPED);
    }

    private Block place(World world, int x, Material type) {
        Block block = world.getBlockAt(x, Y, 8);
        block.setType(type);
        BlockPlaceEvent event = new BlockPlaceEvent(
                block,
                block.getState(),
                world.getBlockAt(x, Y - 1, 8),
                new ItemStack(type),
                player,
                true,
                EquipmentSlot.HAND);
        glimmer.onLight(event);
        return block;
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
