package com.uxplima.uxmskyblock.bukkit.acid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.PresetConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandsPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shipped AcidIsland preset lays the starter platform and then the acid sea around it: water in the
 * air around the island, a floor under the water, and every block the island already has kept.
 */
class ANewAcidIslandStandsInItsSeaTest extends MockBukkitHarness {

    private static final int CENTER = 5_000;
    private static final int Y = 99;
    private static final AcidIslandConfiguration.Sea SEA = new AcidIslandConfiguration.Sea(2, 3, 20, "SAND");

    private final Map<IslandId, Integer> recorded = new HashMap<>();
    private final Set<String> regions = new HashSet<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private AcidIslandService service;

    @SuppressWarnings("NullAway.Init")
    private StarterSchematicEngine engine;

    @BeforeEach
    void setUpEngine() {
        world = server.addSimpleWorld("skyblock");
        service = new AcidIslandService(new AcidIslandsPort() {
            @Override
            public Map<IslandId, Integer> findAll() {
                return Map.copyOf(recorded);
            }

            @Override
            public java.util.OptionalInt find(IslandId islandId) {
                Integer level = recorded.get(islandId);
                return level == null ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(level);
            }

            @Override
            public void add(IslandId islandId, int seaLevel) {
                recorded.putIfAbsent(islandId, seaLevel);
            }
        });
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        engine = new StarterSchematicEngine();
        engine.actions().register(new AcidSeaStart(service, scheduler, SEA));
    }

    @Test
    @DisplayName("The island stands in water on a floor, and its own blocks are kept")
    void theIslandStandsInItsSea() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();
        IslandId island = IslandId.of(UUID.randomUUID());

        engine.start(new IslandStart(world, island, CENTER, Y, CENTER, preset));

        int surface = Y - SEA.belowIsland();
        assertThat(world.getBlockAt(CENTER + 15, surface, CENTER).getType()).isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER - 20, surface - 2, CENTER + 20).getType())
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(CENTER + 15, surface - 3, CENTER).getType()).isEqualTo(Material.SAND);
        assertThat(world.getBlockAt(CENTER + 15, surface + 1, CENTER).getType())
                .describedAs("nothing above the surface")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER + 21, surface, CENTER).getType())
                .describedAs("nothing past the radius")
                .isEqualTo(Material.AIR);
        assertThat(world.getBlockAt(CENTER, Y - 2, CENTER).getType())
                .describedAs("the platform's bedrock is kept where the sea reaches it")
                .isEqualTo(Material.BEDROCK);
        assertThat(world.getBlockAt(CENTER, Y, CENTER).getType()).isEqualTo(Material.GRASS_BLOCK);
        assertThat(service.seaLevel(island)).hasValue(surface);
        assertThat(regions)
                .describedAs("each chunk of the sea is laid on its own region's thread")
                .hasSize(9);
    }

    @Test
    @DisplayName("The preset plays AcidIsland and lays the platform before the sea")
    void thePresetIsAcidIsland() throws Exception {
        StarterPreset preset = shipped().catalogue().findById("acid_island").orElseThrow();

        assertThat(preset.mode()).isEqualTo(GameModeType.ACID_ISLAND);
        assertThat(preset.start()).containsExactly(StarterPreset.PLATFORM, AcidSeaStart.ACTION);
        assertThat(shipped()
                        .startableWith(new StarterSchematicEngine().actions()::knowsAll)
                        .catalogue()
                        .findById("acid_island"))
                .describedAs("not offered where AcidIsland is off")
                .isEmpty();
    }

    @Test
    @DisplayName("The shipped file reads as the shipped numbers, and a sea without depth falls back")
    void theShippedFile() throws Exception {
        AcidIslandConfiguration file = AcidIslandConfiguration.load(
                HoconConfigurationLoader.builder().buildAndLoadString(resource("modules/acidisland.conf")));
        AcidIslandConfiguration odd = AcidIslandConfiguration.load(HoconConfigurationLoader.builder()
                .buildAndLoadString(
                        "sea { depth = 0 }\nhazard { check-every = \"500ms\", water-damage = 4, water-effects = [] }"));

        assertThat(file).isEqualTo(AcidIslandConfiguration.defaultConfiguration());
        assertThat(odd.sea()).isEqualTo(AcidIslandConfiguration.Sea.SHIPPED);
        assertThat(odd.rules().checkEvery()).isEqualTo(java.time.Duration.ofMillis(500));
        assertThat(odd.rules().waterDamage()).isEqualTo(4.0);
        assertThat(odd.waterEffects()).isEmpty();
        assertThat(odd.rules()).isNotEqualTo(AcidRules.shipped());
        assertThat(file.waterProtection()).isEqualTo(List.of("water_breathing"));
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
