package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A new TradeWinds vessel is recorded and launched on a sea of its own: the sea fills the air around it to
 * three blocks under the arrival, and the ship floats on it with a dry hold, a deck where players arrive,
 * rails and a mast.
 */
class AVesselIsLaunchedOnItsSeaTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final int CENTRE = 8;

    private final Set<IslandId> vessels = new HashSet<>();
    private final ArrayList<String> regions = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private VesselService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpWorld() {
        world = server.addSimpleWorld("sea");
        service = new VesselService(new VesselsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(vessels);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return vessels.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                vessels.add(islandId);
            }
        });
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    regions.add(call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
    }

    @Test
    @DisplayName("The vessel floats on its sea: deck under the arrival, a dry hold, rails, a mast and a sail")
    void theVesselIsLaunched() throws Exception {
        IslandId made = IslandId.of(UUID.randomUUID());

        new VesselStart(service, scheduler, new TradeWindsConfiguration.Sea(20, 6, "SANDSTONE")).apply(start(made));

        assertThat(service.isVessel(made)).isTrue();
        assertThat(type(0, -1, 0)).describedAs("the deck under the arrival").isEqualTo(Material.OAK_PLANKS);
        assertThat(type(0, 0, 0)).describedAs("room to arrive").isEqualTo(Material.AIR);
        assertThat(type(0, -2, 0)).describedAs("the hold is dry").isEqualTo(Material.AIR);
        assertThat(type(0, -3, 0))
                .describedAs("the hold is dry at the waterline too")
                .isEqualTo(Material.AIR);
        assertThat(type(0, -4, 0)).describedAs("the keel").isEqualTo(Material.SPRUCE_PLANKS);
        assertThat(type(0, -3, VesselStart.HALF_BEAM))
                .describedAs("the hull's side")
                .isEqualTo(Material.SPRUCE_PLANKS);
        assertThat(type(0, 0, VesselStart.HALF_BEAM)).describedAs("a rail").isEqualTo(Material.OAK_FENCE);
        assertThat(type(VesselStart.MAST, 3, 0)).describedAs("the mast").isEqualTo(Material.OAK_LOG);
        assertThat(type(VesselStart.MAST, 4, 1)).describedAs("the sail").isEqualTo(Material.WHITE_WOOL);
        assertThat(type(VesselStart.HALF_LENGTH, -1, 1))
                .describedAs("the bow narrows to a point")
                .isNotEqualTo(Material.OAK_PLANKS);
        assertThat(type(VesselStart.HALF_LENGTH, -1, 0)).isEqualTo(Material.OAK_PLANKS);
        assertThat(type(15, -3, 0)).describedAs("the sea's surface").isEqualTo(Material.WATER);
        assertThat(type(15, -2, 0)).describedAs("air over the sea").isEqualTo(Material.AIR);
        assertThat(type(15, -8, 0)).describedAs("the sea's deepest water").isEqualTo(Material.WATER);
        assertThat(type(15, -9, 0)).describedAs("the sea floor").isEqualTo(Material.SANDSTONE);
        assertThat(type(-20, -3, 20)).isEqualTo(Material.WATER);
        assertThat(type(21, -3, 0)).describedAs("the sea ends at its radius").isEqualTo(Material.AIR);
        assertThat(regions)
                .describedAs("every chunk but the centre's is laid on the thread of its region")
                .doesNotContain("0,0")
                .contains("-1,-1", "1,1");
    }

    @Test
    @DisplayName("A ship that crosses a chunk border gets every part of it, its hold dry on both sides")
    void aShipAcrossChunks() throws Exception {
        IslandId made = IslandId.of(UUID.randomUUID());
        int x = 15;

        new VesselStart(service, scheduler, new TradeWindsConfiguration.Sea(20, 6, "SANDSTONE"))
                .apply(new IslandStart(world, made, x, Y, CENTRE, preset()));

        assertThat(world.getBlockAt(x + 3, Y - 2, CENTRE).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(x + 3, Y - 1, CENTRE).getType()).isEqualTo(Material.OAK_PLANKS);
        assertThat(world.getBlockAt(x - 3, Y - 2, CENTRE).getType()).isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("The shipped file reads as the shipped sea, and a sea that cannot be laid falls back")
    void theShippedFile() throws Exception {
        assertThat(TradeWindsConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/tradewinds.conf"))))
                .isEqualTo(TradeWindsConfiguration.defaultConfiguration());
        assertThat(sea("sea { radius = 7 }")).isEqualTo(TradeWindsConfiguration.Sea.SHIPPED);
        assertThat(sea("sea { radius = 129 }")).isEqualTo(TradeWindsConfiguration.Sea.SHIPPED);
        assertThat(sea("sea { depth = 1 }")).isEqualTo(TradeWindsConfiguration.Sea.SHIPPED);
        assertThat(sea("sea { radius = 10, depth = 3, floor = STONE }"))
                .isEqualTo(new TradeWindsConfiguration.Sea(10, 3, "STONE"));
    }

    @Test
    @DisplayName("The shipped preset launches a TradeWinds vessel, with no Nether and no End")
    void theShippedPreset() throws Exception {
        StarterPreset preset = preset();

        assertThat(preset.mode()).isEqualTo(GameModeType.TRADEWINDS);
        assertThat(preset.start()).containsExactly(VesselStart.ACTION);
        assertThat(preset.dimensions().resolve(DimensionId.THE_NETHER)).isEmpty();
        assertThat(preset.dimensions().resolve(DimensionId.THE_END)).isEmpty();
    }

    private Material type(int dx, int dy, int dz) {
        return world.getBlockAt(CENTRE + dx, Y + dy, CENTRE + dz).getType();
    }

    private IslandStart start(IslandId made) throws Exception {
        return new IslandStart(world, made, CENTRE, Y, CENTRE, preset());
    }

    private StarterPreset preset() throws Exception {
        return PresetConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                .catalogue()
                .findById("tradewinds")
                .orElseThrow();
    }

    private static TradeWindsConfiguration.Sea sea(String hocon) throws Exception {
        return TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon))
                .sea();
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
