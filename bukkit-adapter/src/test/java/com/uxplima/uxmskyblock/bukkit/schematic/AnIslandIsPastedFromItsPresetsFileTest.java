package com.uxplima.uxmskyblock.bukkit.schematic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmlib.schematic.paper.PasteOptions;
import com.uxplima.uxmlib.schematic.paper.PasteReport;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * A new island is pasted from its preset's file where one stands, and the shipped platform is laid where
 * none does or where the one that does cannot be read. What the preset does after the paste, and the
 * player's arrival, wait for the last block of it.
 */
class AnIslandIsPastedFromItsPresetsFileTest extends MockBukkitHarness {

    private static final int Y = 80;

    @TempDir
    Path folder;

    private final List<Location> pastedAt = new ArrayList<>();
    private final List<Runnable> handedBack = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private CompletableFuture<PasteReport> pasting = CompletableFuture.completedFuture(faithful());
    private IslandSchematics schematics;
    private StarterSchematicEngine engine;
    private World world;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        schematics = new IslandSchematics(
                folder,
                Runnable::run,
                (schematic, at, options) -> {
                    pastedAt.add(at);
                    place(schematic, at);
                    return pasting;
                },
                (w, corner, other, origin, options) -> CompletableFuture.failedFuture(new AssertionError()),
                PasteOptions.DEFAULT);
        SchedulerPort regions = mock(SchedulerPort.class);
        doAnswer(call -> handedBack.add(call.getArgument(3)))
                .when(regions)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
        engine = new StarterSchematicEngine(
                null,
                schematics,
                regions,
                dimension -> dimension == IslandDimensionType.NETHER ? Optional.of("island_nether") : Optional.empty());
        engine.actions().register(new CreationActionProvider<>() {
            @Override
            public String actionId() {
                return "test:after";
            }

            @Override
            public void apply(IslandStart start) {
                ran.add("after");
            }
        });
    }

    @Test
    @DisplayName("A preset whose file stands is pasted from it, its point on the block players stand on")
    void pastedFromItsFile() {
        schematics.writeIfMissing("schematics/classic.schem", goldIsland()).join();

        engine.start(start(preset("classic", List.of(StarterPreset.PLATFORM)))).join();

        assertThat(pastedAt).singleElement().satisfies(at -> {
            assertThat(at.getBlockX()).isZero();
            assertThat(at.getBlockY()).isEqualTo(Y);
            assertThat(at.getBlockZ()).isZero();
        });
        assertThat(world.getBlockAt(0, Y, 0).getType()).isEqualTo(Material.GOLD_BLOCK);
        assertThat(world.getBlockAt(0, Y - 2, 0).getType())
                .describedAs("the shipped platform's bedrock is not laid under a file's island")
                .isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("A preset with no file lays the shipped platform, at once")
    void noFileLaysThePlatform() {
        CompletableFuture<Void> started = engine.start(start(preset("desert", List.of(StarterPreset.PLATFORM))));

        assertThat(pastedAt).isEmpty();
        assertThat(handedBack).hasSize(1);
        handedBack.removeFirst().run();
        assertThat(started).isCompleted();
        assertThat(world.getBlockAt(0, Y, 0).getType()).isEqualTo(Material.SAND);
        assertThat(world.getBlockAt(0, Y - 2, 0).getType()).isEqualTo(Material.BEDROCK);
    }

    @Test
    @DisplayName("A file that cannot be read lays the shipped platform, so nobody arrives over the void")
    void aBrokenFileLaysThePlatform() throws Exception {
        Files.createDirectories(folder.resolve("schematics"));
        Files.writeString(folder.resolve("schematics/classic.schem"), "not a schematic");

        CompletableFuture<Void> started = engine.start(start(preset("classic", List.of(StarterPreset.PLATFORM))));
        while (!handedBack.isEmpty()) {
            handedBack.removeFirst().run();
        }

        assertThat(started).isCompleted();
        assertThat(world.getBlockAt(0, Y, 0).getType()).isEqualTo(Material.GRASS_BLOCK);
    }

    @Test
    @DisplayName("What follows the paste waits for its last block, then runs on the island's region")
    void theNextActionWaits() {
        schematics.writeIfMissing("schematics/classic.schem", goldIsland()).join();
        pasting = new CompletableFuture<>();

        CompletableFuture<Void> started =
                engine.start(start(preset("classic", List.of(StarterPreset.PLATFORM, "test:after"))));

        assertThat(ran).isEmpty();
        assertThat(started).isNotDone();
        pasting.complete(faithful());
        assertThat(ran)
                .describedAs("not on whichever thread finished the paste")
                .isEmpty();
        handedBack.removeFirst().run();
        assertThat(ran).containsExactly("after");
        assertThat(started).isCompleted();
    }

    @Test
    @DisplayName("A dimension's platform is pasted from the file dimensions.conf names")
    void aDimensionsFile() {
        WorldMock nether = server.addSimpleWorld("skyblock_nether");
        nether.setEnvironment(World.Environment.NETHER);
        schematics
                .writeIfMissing("schematics/island_nether.schem", goldIsland())
                .join();

        engine.build(
                        new IslandStart(
                                nether,
                                IslandId.of(UUID.randomUUID()),
                                0,
                                Y,
                                0,
                                preset("classic", List.of(StarterPreset.PLATFORM))),
                        List.of(com.uxplima.uxmskyblock.core.domain.preset.StartTemplateBundle.DIMENSION_PLATFORM))
                .join();

        assertThat(nether.getBlockAt(0, Y, 0).getType()).isEqualTo(Material.GOLD_BLOCK);
    }

    @Test
    @DisplayName("The shipped platforms are written as the files their presets and dimensions name, none overwritten")
    void theShippedPlatformsAreWritten() throws Exception {
        schematics.writeIfMissing("schematics/desert.schem", goldIsland()).join();

        List<String> written = engine.writeShippedPlatforms(
                        List.of(
                                preset("classic", List.of(StarterPreset.PLATFORM)),
                                preset("desert", List.of(StarterPreset.PLATFORM)),
                                preset("oneblock", List.of("uxm:oneblock"))),
                        Map.of(IslandDimensionType.NETHER, "island_nether"),
                        4671)
                .join();

        assertThat(written).containsExactlyInAnyOrder("schematics/classic.schem", "schematics/island_nether.schem");
        assertThat(Files.exists(folder.resolve("schematics/oneblock.schem")))
                .describedAs("a preset that lays no platform has no platform to write")
                .isFalse();
        assertThat(schematics.read("schematics/desert.schem").join())
                .hasValueSatisfying(kept -> assertThat(kept.blockAt(0, 0, 0)).isEqualTo("minecraft:gold_block"));
        assertThat(schematics.read("schematics/classic.schem").join())
                .hasValueSatisfying(classic -> assertThat(classic.blockAt(2, 2, 2))
                        .isEqualTo(Material.GRASS_BLOCK.createBlockData().getAsString()));
    }

    private IslandStart start(StarterPreset preset) {
        return new IslandStart(world, IslandId.of(UUID.randomUUID()), 0, Y, 0, preset);
    }

    private static StarterPreset preset(String id, List<String> start) {
        return new StarterPreset(
                id, id, "", "schematics/" + id + ".schem", IslandBiome.PLAINS, GameModeType.SKYBLOCK, start);
    }

    private static Schematic goldIsland() {
        return Schematic.builder(1, 1, 1)
                .dataVersion(4671)
                .block(0, 0, 0, "minecraft:gold_block")
                .build();
    }

    private static void place(Schematic schematic, Location at) {
        World target = at.getWorld();
        Vec3i offset = schematic.offset();
        for (int x = 0; x < schematic.width(); x++) {
            for (int y = 0; y < schematic.height(); y++) {
                for (int z = 0; z < schematic.length(); z++) {
                    target.getBlockAt(
                                    at.getBlockX() + offset.x() + x,
                                    at.getBlockY() + offset.y() + y,
                                    at.getBlockZ() + offset.z() + z)
                            .setBlockData(Bukkit.createBlockData(schematic.blockAt(x, y, z)), false);
                }
            }
        }
    }

    private static PasteReport faithful() {
        return new PasteReport(1, 0, 0, Set.of(), 0, Set.of(), Map.of(), List.of());
    }
}
