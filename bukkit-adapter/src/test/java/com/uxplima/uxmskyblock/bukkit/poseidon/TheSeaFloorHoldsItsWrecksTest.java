package com.uxplima.uxmskyblock.bukkit.poseidon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wrecks and ruins lie on the sea floor around a Poseidon island, a set distance out and spread evenly.
 * A template fills only water and air, a block that can hold water holds it in the sea, air and markers
 * lay nothing but a chest a marker asks for, and each chest gets the next loot table in the list.
 */
class TheSeaFloorHoldsItsWrecksTest extends MockBukkitHarness {

    private static final int CENTER = 8_000;
    private static final int Y = 100;
    private static final PoseidonConfiguration.Ocean OCEAN = new PoseidonConfiguration.Ocean(6, 4, 64, "SANDSTONE");
    private static final int FLOOR = Y - OCEAN.depth();

    private final Set<String> regions = new HashSet<>();
    private final Map<String, String> given = new HashMap<>();
    private final List<String> asked = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World world;

    @SuppressWarnings("NullAway.Init")
    private SchedulerPort scheduler;

    @BeforeEach
    void setUpSea() {
        world = server.addSimpleWorld("skyblock");
        scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    regions.add(call.getArgument(1) + "," + call.getArgument(2));
                    call.getArgument(3, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onRegion(anyString(), anyInt(), anyInt(), any(Runnable.class));
    }

    @Test
    @DisplayName("A template lies on the floor a set distance out, in water, and keeps what it meets")
    void aWreckLiesOnTheFloor() {
        flood(CENTER + 8, CENTER - 4, 12);
        world.getBlockAt(CENTER + 10, FLOOR + 1, CENTER).setType(Material.GLOWSTONE);

        start(new PoseidonConfiguration.Wreck(List.of("test:ship"), 1, 10, List.of("test:supply", "test:treasure")))
                .apply(islandStart(fixedEast()));

        int x = CENTER + 10 - 2;
        int z = CENTER - 1;
        Block hull = world.getBlockAt(x, FLOOR, z);
        assertThat(hull.getType()).isEqualTo(Material.OAK_PLANKS);
        assertThat(world.getBlockAt(x + 2, FLOOR + 1, z + 1).getType())
                .describedAs("what the sea floor already held is kept")
                .isEqualTo(Material.GLOWSTONE);
        assertThat(world.getBlockAt(x + 1, FLOOR + 1, z).getType())
                .describedAs("air in a template lays nothing, so the hull stays full of water")
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(x + 3, FLOOR + 1, z).getType())
                .describedAs("a structure block lays nothing")
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(x + 4, FLOOR + 1, z).getType())
                .describedAs("a marker that asks for no chest lays nothing")
                .isEqualTo(Material.WATER);
        assertThat(world.getBlockAt(x, FLOOR + 1, z).getBlockData())
                .isInstanceOfSatisfying(
                        Waterlogged.class,
                        chest -> assertThat(chest.isWaterlogged()).isTrue());
        assertThat(given).containsEntry(at(x, FLOOR + 1, z), "test:supply");
        assertThat(world.getBlockAt(x, FLOOR + 2, z).getType())
                .describedAs("a marker that asks for a chest gets one")
                .isEqualTo(Material.CHEST);
        assertThat(given).containsEntry(at(x, FLOOR + 2, z), "test:treasure").hasSize(2);
        assertThat(asked).containsExactly("test:ship");
    }

    @Test
    @DisplayName("Several are spread evenly around the island, and a chest in the air holds no water")
    void severalAreSpread() {
        start(new PoseidonConfiguration.Wreck(List.of("test:ship"), 4, 20, List.of()))
                .apply(islandStart(fixedEast()));

        int found = 0;
        for (int[] side : new int[][] {{20, 0}, {0, 20}, {-20, 0}, {0, -20}}) {
            Block corner = world.getBlockAt(CENTER + side[0] - 2, FLOOR, CENTER + side[1] - 1);
            Block near = world.getBlockAt(CENTER + side[0], FLOOR, CENTER + side[1]);
            if (corner.getType() == Material.OAK_PLANKS || near.getType() == Material.OAK_PLANKS) {
                found++;
            }
        }
        assertThat(found).isEqualTo(4);
        assertThat(regions).isNotEmpty();
        assertThat(world.getBlockAt(CENTER, FLOOR, CENTER).getType())
                .describedAs("nothing is laid on the island itself")
                .isEqualTo(Material.AIR);
    }

    @Test
    @DisplayName("The same island is laid the same way twice, and a template the server lacks lays nothing")
    void theIslandDecides() {
        IslandId island = IslandId.of(UUID.randomUUID());
        PoseidonConfiguration.Wreck wreck =
                new PoseidonConfiguration.Wreck(List.of("test:ship", "test:missing"), 3, 16, List.of());
        start(wreck).apply(new IslandStart(world, island, CENTER, Y, CENTER, preset()));
        List<String> first = List.copyOf(asked);
        asked.clear();
        start(wreck).apply(new IslandStart(world, island, CENTER + 1000, Y, CENTER, preset()));

        assertThat(asked).isEqualTo(first).hasSize(3);
        start(new PoseidonConfiguration.Wreck(List.of("test:missing"), 2, 16, List.of()))
                .apply(islandStart(fixedEast()));
        start(new PoseidonConfiguration.Wreck(List.of(), 2, 16, List.of())).apply(islandStart(fixedEast()));
    }

    private WreckStart start(PoseidonConfiguration.Wreck wreck) {
        return new WreckStart(
                "uxm:shipwreck",
                wreck,
                OCEAN,
                this::template,
                (block, table, seed) -> given.put(at(block.getX(), block.getY(), block.getZ()), table),
                scheduler);
    }

    private Optional<WreckTemplate> template(String key) {
        asked.add(key);
        if (!key.equals("test:ship")) {
            return Optional.empty();
        }
        List<WreckTemplate.Piece> pieces = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 3; z++) {
                pieces.add(piece(x, 0, z, Material.OAK_PLANKS, null));
            }
        }
        pieces.add(piece(0, 1, 0, Material.CHEST, null));
        pieces.add(piece(1, 1, 0, Material.AIR, null));
        pieces.add(piece(2, 1, 1, Material.OAK_PLANKS, null));
        pieces.add(piece(3, 1, 0, Material.STRUCTURE_BLOCK, null));
        pieces.add(piece(4, 1, 0, Material.STRUCTURE_BLOCK, "drowned"));
        pieces.add(piece(0, 2, 0, Material.STRUCTURE_BLOCK, " chest "));
        return Optional.of(new WreckTemplate(5, 3, pieces));
    }

    private static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static WreckTemplate.Piece piece(int x, int y, int z, Material type, @Nullable String marker) {
        BlockData data = type.createBlockData();
        return new WreckTemplate.Piece(x, y, z, data, marker);
    }

    /** An island whose seed turns its single wreck to the east. */
    private IslandId fixedEast() {
        for (int tries = 0; tries < 10_000; tries++) {
            IslandId id = IslandId.of(new UUID(0, tries));
            java.util.Random random =
                    new java.util.Random(id.value().getLeastSignificantBits() ^ "uxm:shipwreck".hashCode());
            double turn = random.nextDouble() * Math.PI * 2;
            if (turn < 0.01) {
                return id;
            }
        }
        throw new IllegalStateException("no seed turns east");
    }

    private IslandStart islandStart(IslandId island) {
        return new IslandStart(world, island, CENTER, Y, CENTER, preset());
    }

    private void flood(int fromX, int fromZ, int size) {
        for (int x = fromX; x < fromX + size; x++) {
            for (int z = fromZ; z < fromZ + size; z++) {
                for (int y = FLOOR; y <= FLOOR + 3; y++) {
                    world.getBlockAt(x, y, z).setType(Material.WATER);
                }
            }
        }
    }

    private static StarterPreset preset() {
        return new StarterPreset(
                "poseidon",
                "Poseidon",
                "",
                "schematics/poseidon.schem",
                com.uxplima.uxmskyblock.core.domain.biome.IslandBiome.PLAINS);
    }
}
