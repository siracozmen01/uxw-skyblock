package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonIslandsPort;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StartTemplate;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shipped Poseidon preset lays the starter platform on the sea floor and then the ocean over it:
 * water from a floor under the island to well above where players arrive, filling only air, and the
 * island recorded as a Poseidon island. Its Nether is flooded the same way.
 */
class ANewPoseidonIslandLiesUnderItsOceanTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final PoseidonConfiguration.Ocean OCEAN = new PoseidonConfiguration.Ocean(6, 3, 20, "SAND");

    private final Set<IslandId> recorded = new HashSet<>();
    private final Set<String> regions = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private PoseidonService service;

    @SuppressWarnings("NullAway.Init")
    private StarterSchematicEngine engine;

    @BeforeEach
    void setUpEngine() {
        world = server.addSimpleWorld("skyblock");
        service = new PoseidonService(new PoseidonIslandsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.copyOf(recorded);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return recorded.contains(islandId);
            }

            @Override
            public void add(IslandId islandId) {
                recorded.add(islandId);
            }
        });
        SchedulerPort scheduler = mock(SchedulerPort.class);
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
        engine = new StarterSchematicEngine();
        engine.actions().register(new OceanStart(service, scheduler, OCEAN));
    }

    @Test
    @DisplayName("The island lies under water on a floor, its own blocks are kept, and it is a Poseidon island")
    void theIslandLiesUnderItsOcean() throws Exception {
        IslandId island = IslandId.of(UUID.randomUUID());

        engine.start(new IslandStart(world, island, CENTER, Y, CENTER, preset()));

        int surface = Y + OCEAN.above();
        int bottom = Y - OCEAN.depth();
        assertThat(world.getBlockAt(CENTER, Y + 2, CENTER).getType())
                .describedAs("water where players arrive")
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER + 15, surface, CENTER).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER - 20, bottom, CENTER + 20).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER + 15, bottom - 1, CENTER).getType())
                .describedAs("sand, which falls, is laid as sandstone")
                .isEqualTo(Material.SANDSTONE);
        assertThat(world.getBlockAt(CENTER + 15, surface + 1, CENTER).getType())
                .describedAs("nothing above the surface")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER + 21, Y, CENTER).getType())
                .describedAs("nothing past the radius")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType())
                .describedAs("the sea floor platform is kept")
                .isEqualTo(Material.SAND);
        assertThat(world.getBlockAt(CENTER, Y - 2, CENTER).getType()).isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTER + 1, Y + 1, CENTER).getType()).isEqualTo(Material.CHEST);
        assertThat(service.isPoseidon(island)).isTrue();
        assertThat(regions)
                .describedAs("each chunk but the centre's is laid on its own region's thread")
                .hasSize(8)
                .doesNotContain((CENTER >> 4) + "," + (CENTER >> 4));
    }

    @Test
    @DisplayName("The preset plays Poseidon, lays the platform before the ocean and floods its Nether")
    void thePresetIsPoseidon() throws Exception {
        StarterPreset preset = preset();

        assertThat(preset.mode()).isEqualTo(GameModeType.POSEIDON);
        assertThat(preset.start()).containsExactly(StarterPreset.PLATFORM, OceanStart.ACTION);
        assertThat(preset.dimensions().resolve(DimensionId.THE_NETHER))
                .get()
                .extracting(StartTemplate::actions)
                .isEqualTo(List.of("uxm:dimension-platform", OceanStart.ACTION));
        assertThat(shipped()
                        .startableWith(new StarterSchematicEngine().actions()::knowsAll)
                        .catalogue()
                        .findById("poseidon"))
                .describedAs("not offered where Poseidon is off")
                .isEmpty();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped numbers, and an ocean without room falls back")
    void theShippedFile() throws Exception {
        PoseidonConfiguration file = PoseidonConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/poseidon.conf")));
        PoseidonConfiguration odd = PoseidonConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString("enabled = false\nocean { above = 1, depth = 5 }"));

        assertThat(file).isEqualTo(PoseidonConfiguration.defaultConfiguration());
        assertThat(odd.enabled()).isFalse();
        assertThat(odd.ocean()).isEqualTo(PoseidonConfiguration.Ocean.SHIPPED);
    }

    private StarterPreset preset() throws Exception {
        return shipped().catalogue().findById("poseidon").orElseThrow();
    }

    private PresetConfiguration shipped() throws Exception {
        return PresetConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/presets.conf")));
    }

    private String resource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
