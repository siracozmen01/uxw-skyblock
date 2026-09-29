package com.uxplima.uxmskyblock.bukkit.brix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.brix.BrixPlotsPort;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A new Brix plot is recorded and laid on the flat ground the operator wrote, top layer where players
 * arrive; a ground of no layers leaves the plot void.
 */
class APlotIsLaidOnItsGroundTest extends MockBukkitHarness {

    private static final int Y = 100;
    private static final int CENTRE = 8;

    private final Set<IslandId> plots = new HashSet<>();
    private final List<String> regions = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private BrixService service;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpWorld() {
        world = server.addSimpleWorld("plots");
        service = new BrixService(new BrixPlotsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(plots);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return plots.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                plots.add(islandId);
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
    @DisplayName("The ground is laid layer by layer under the arrival, as wide as written, filling only air")
    void theGroundIsLaid() throws Exception {
        IslandId made = IslandId.of(UUID.randomUUID());
        world.getBlockAt(CENTRE + 1, Y, CENTRE).setType(Material.OAK_PLANKS);
        BrixConfiguration.Ground ground =
                new BrixConfiguration.Ground(20, List.of(Material.GRASS_BLOCK, Material.DIRT, Material.BEDROCK));

        new PlotStart(service, scheduler, ground).apply(start(made));

        assertThat(service.isPlot(made)).isTrue();
        assertThat(world.getBlockAt(CENTRE, Y, CENTRE).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getBlockAt(CENTRE, Y - 1, CENTRE).getType()).isEqualTo(Material.DIRT);
        assertThat(world.getBlockAt(CENTRE, Y - 2, CENTRE).getType()).isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTRE, Y - 3, CENTRE).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTRE, Y + 1, CENTRE).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTRE + 1, Y, CENTRE).getType())
                .describedAs("what already stands is kept")
                .isEqualTo(Material.OAK_PLANKS);
        assertThat(world.getBlockAt(CENTRE - 20, Y, CENTRE + 20).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(world.getBlockAt(CENTRE + 20, Y - 2, CENTRE - 20).getType()).isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTRE + 21, Y, CENTRE).getType()).isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTRE, Y, CENTRE - 21).getType()).isEqualTo(Material.AIR);
        assertThat(regions)
                .describedAs("every chunk but the centre's is laid on the thread of its region")
                .doesNotContain("0,0")
                .contains("-1,-1", "1,1", "-1,1", "1,-1");
    }

    @Test
    @DisplayName("A ground of no layers lays nothing, and the plot is still a plot")
    void aVoidPlot() throws Exception {
        IslandId made = IslandId.of(UUID.randomUUID());

        new PlotStart(service, scheduler, new BrixConfiguration.Ground(20, List.of())).apply(start(made));

        assertThat(service.isPlot(made)).isTrue();
        assertThat(world.getBlockAt(CENTRE, Y, CENTRE).getType()).isEqualTo(Material.AIR);
        assertThat(regions).isEmpty();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped ground, and a ground that cannot be laid falls back")
    void theShippedFile() throws Exception {
        assertThat(BrixConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/brix.conf"))))
                .isEqualTo(BrixConfiguration.defaultConfiguration());
        assertThat(ground("ground { radius = 5, layers = [\"stone\"] }"))
                .isEqualTo(new BrixConfiguration.Ground(5, List.of(Material.STONE)));
        assertThat(ground("ground { layers = [] }").layers()).isEmpty();
        assertThat(ground("ground { radius = 0 }").radius()).isZero();
        assertThat(ground("ground { radius = 128 }").radius()).isEqualTo(128);
        assertThat(ground("ground { radius = 129 }")).isEqualTo(BrixConfiguration.Ground.SHIPPED);
        assertThat(ground("ground { radius = -1 }")).isEqualTo(BrixConfiguration.Ground.SHIPPED);
        assertThat(ground("ground { layers = [\"NOT_A_BLOCK\"] }")).isEqualTo(BrixConfiguration.Ground.SHIPPED);
        assertThatThrownBy(() -> new BrixConfiguration.Ground(1, List.of(Material.DIAMOND)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ground("ground { layers = [\"DIAMOND\"] }"))
                .describedAs("an item is no layer")
                .isEqualTo(BrixConfiguration.Ground.SHIPPED);
        assertThat(ground("ground { layers = [" + "\"DIRT\",".repeat(64) + "] }")
                        .layers())
                .hasSize(64);
        assertThat(ground("ground { layers = [" + "\"DIRT\",".repeat(65) + "] }"))
                .isEqualTo(BrixConfiguration.Ground.SHIPPED);
    }

    @Test
    @DisplayName("The shipped preset makes a Brix plot on its ground, with no Nether and no End")
    void theShippedPreset() throws Exception {
        StarterPreset preset = brixPreset();

        assertThat(preset.mode()).isEqualTo(GameModeType.BRIX);
        assertThat(preset.start()).containsExactly(PlotStart.ACTION);
        assertThat(preset.dimensions().resolve(DimensionId.THE_NETHER)).isEmpty();
        assertThat(preset.dimensions().resolve(DimensionId.THE_END)).isEmpty();
    }

    private IslandStart start(IslandId made) throws Exception {
        return new IslandStart(world, made, CENTRE, Y, CENTRE, brixPreset());
    }

    private StarterPreset brixPreset() throws Exception {
        return PresetConfiguration.load(
                        HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")))
                .catalogue()
                .findById("brix")
                .orElseThrow();
    }

    private static BrixConfiguration.Ground ground(String hocon) throws Exception {
        return BrixConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString(hocon))
                .ground();
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
