package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * How far apart islands stand is the operator's, until the first island stands away from the centre.
 *
 * <p>The spacing was a constant of 5120 blocks in the code. It is read from {@code grid.spacing}
 * now. A world whose islands already stand at one spacing keeps it, because a new island placed by
 * another lands inside an old one.
 */
class TheGridSpacingIsTheOperatorsTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-alpha");

    private final PersistenceBootstrap persistence = PersistenceBootstrap.createSqliteInMemory();

    @AfterEach
    void tearDown() throws Exception {
        persistence.close();
    }

    @Test
    @DisplayName("A new world places its islands at the spacing the operator wrote")
    void theWrittenSpacingPlacesTheIslands() throws Exception {
        assertThat(PersistenceWiring.useGridSpacing(persistence, root("grid { spacing = 2000 }")))
                .isEqualTo(2000);

        persistence.worldGridAllocationPort().allocateNext(NODE, "world", null);
        WorldGridAllocation second = persistence.worldGridAllocationPort().allocateNext(NODE, "world", null);

        assertThat(second.centerX()).isEqualTo(2000);
        assertThat(second.centerZ()).isZero();
        assertThat(persistence.gridAllocator().gridSpacing())
                .describedAs("the grid the creation service reads is the one the islands were placed by")
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("Islands already placed keep their spacing whatever the file says now")
    void placedIslandsKeepTheirSpacing() throws Exception {
        PersistenceWiring.useGridSpacing(persistence, root("grid { }"));
        persistence.worldGridAllocationPort().allocateNext(NODE, "world", null);
        persistence.worldGridAllocationPort().allocateNext(NODE, "world", null);

        assertThat(PersistenceWiring.useGridSpacing(persistence, root("grid { spacing = 2000 }")))
                .isEqualTo(SpiralGridCoordinateAllocator.DEFAULT_GRID_SPACING);
        WorldGridAllocation third = persistence.worldGridAllocationPort().allocateNext(NODE, "world", null);

        assertThat(third.centerX()).isEqualTo(SpiralGridCoordinateAllocator.DEFAULT_GRID_SPACING);
        assertThat(third.centerZ()).isEqualTo(SpiralGridCoordinateAllocator.DEFAULT_GRID_SPACING);
    }

    @Test
    @DisplayName("No value and one that is not positive keep the default")
    void anythingElseKeepsTheDefault() throws Exception {
        for (String written : new String[] {"grid { }", "grid { spacing = 0 }", "grid { spacing = -64 }"}) {
            assertThat(PersistenceWiring.useGridSpacing(persistence, root(written)))
                    .describedAs(written)
                    .isEqualTo(SpiralGridCoordinateAllocator.DEFAULT_GRID_SPACING);
        }
        assertThat(PersistenceWiring.useGridSpacing(persistence, null))
                .isEqualTo(SpiralGridCoordinateAllocator.DEFAULT_GRID_SPACING);
    }

    @Test
    @DisplayName("The boot resolves the spacing, and nothing in the plugin builds a grid of its own")
    void theBootUsesTheResolvedGrid() throws Exception {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java");
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(main)) {
            for (java.nio.file.Path file :
                    files.filter(f -> f.toString().endsWith(".java")).toList()) {
                assertThat(java.nio.file.Files.readString(file))
                        .describedAs("%s builds a grid that ignores grid.spacing", file)
                        .doesNotContain("new SpiralGridCoordinateAllocator(")
                        .doesNotContain("new SpiralWorldGridService()");
            }
        }
        java.nio.file.Path wiring = main.resolve("com/uxplima/uxmskyblock/bukkit/bootstrap");
        assertThat(java.nio.file.Files.readString(wiring.resolve("PersistenceWiring.java")))
                .contains("useGridSpacing(persistenceBootstrap, rootNode);");
        assertThat(java.nio.file.Files.readString(wiring.resolve("GameplayCreationWiring.java")))
                .contains("persistence.gridAllocator()");
    }

    private static ConfigurationNode root(String hocon) throws Exception {
        return HoconConfigurationLoader.builder().buildAndLoadString(hocon);
    }
}
