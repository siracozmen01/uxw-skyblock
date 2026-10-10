package com.uxplima.uxmskyblock.bukkit.schematic;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Location;
import org.bukkit.Material;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.paper.PasteOptions;
import com.uxplima.uxmlib.schematic.paper.PasteReport;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * An island world's spawn has ground under it, so a player with no island, one who has just reset theirs,
 * stands there instead of falling, being caught by the void guard and sent back to fall again.
 */
class APlayerWithNoIslandHasGroundTest extends MockBukkitHarness {

    private static final int SPAWN_Y = 100;

    @TempDir
    Path folder;

    private final List<Location> pasted = new ArrayList<>();
    private final List<Runnable> handedBack = new ArrayList<>();
    private WorldMock world;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock");
        world.setSpawnLocation(10, SPAWN_Y, 10);
    }

    @Test
    @DisplayName("Where nothing stands under the spawn, the shipped ground is laid under it")
    void groundIsLaid() {
        assertThat(SpawnGround.hasGround(world, world.getSpawnLocation())).isFalse();

        assertThat(new SpawnGround(null).ensure(world, handedBack::add).join()).isTrue();

        assertThat(world.getBlockAt(10, SPAWN_Y - 1, 10).getType()).isEqualTo(Material.SMOOTH_STONE);
        assertThat(world.getBlockAt(13, SPAWN_Y - 1, 7).getType()).isEqualTo(Material.SMOOTH_STONE);
        assertThat(SpawnGround.hasGround(world, world.getSpawnLocation())).isTrue();
    }

    @Test
    @DisplayName("Ground already there, an operator's lobby, is left alone")
    void groundIsLeftAlone() {
        world.getBlockAt(10, SPAWN_Y - 20, 10).setType(Material.OAK_PLANKS);

        assertThat(new SpawnGround(files()).ensure(world, handedBack::add).join())
                .isFalse();

        assertThat(pasted).isEmpty();
        assertThat(world.getBlockAt(10, SPAWN_Y - 1, 10).getType()).isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("The operator's file is the ground, pasted on the block under the spawn")
    void theFileIsTheGround() {
        IslandSchematics files = files();
        files.writeIfMissing(SpawnGround.FILE, SpawnGround.shipped(4671)).join();

        assertThat(new SpawnGround(files).ensure(world, handedBack::add).join()).isTrue();

        assertThat(pasted).singleElement().satisfies(at -> {
            assertThat(at.getBlockX()).isEqualTo(10);
            assertThat(at.getBlockY()).isEqualTo(SPAWN_Y - 1);
            assertThat(at.getBlockZ()).isEqualTo(10);
        });
    }

    @Test
    @DisplayName("Without the file the ground is laid plain, on the thread that owns the spawn")
    void noFileLaysItPlain() throws Exception {
        Files.createDirectories(folder);

        CompletableFuture<Boolean> laid = new SpawnGround(files()).ensure(world, handedBack::add);

        assertThat(world.getBlockAt(10, SPAWN_Y - 1, 10).getType()).isEqualTo(Material.AIR);
        assertThat(handedBack).hasSize(1);
        handedBack.removeFirst().run();
        assertThat(laid.join()).isTrue();
        assertThat(world.getBlockAt(10, SPAWN_Y - 1, 10).getType()).isEqualTo(Material.SMOOTH_STONE);
    }

    @Test
    @DisplayName("The file the plugin writes is the ground it lays")
    void theFileIsThePlainGround() {
        SpawnGround.lay(new Location(world, 0, 64, 0));
        Schematic file = SpawnGround.shipped(4671);

        for (int x = 0; x < file.width(); x++) {
            for (int z = 0; z < file.length(); z++) {
                assertThat(file.blockAt(x, 0, z))
                        .isEqualTo(world.getBlockAt(
                                        x + file.offset().x(),
                                        64,
                                        z + file.offset().z())
                                .getBlockData()
                                .getAsString());
            }
        }
    }

    private IslandSchematics files() {
        return new IslandSchematics(
                folder,
                Runnable::run,
                (schematic, at, options) -> {
                    pasted.add(at);
                    return CompletableFuture.completedFuture(
                            new PasteReport(49, 0, 0, Set.of(), 0, Set.of(), Map.of(), List.of()));
                },
                (w, corner, other, origin, options) -> CompletableFuture.failedFuture(new AssertionError()),
                PasteOptions.DEFAULT);
    }
}
