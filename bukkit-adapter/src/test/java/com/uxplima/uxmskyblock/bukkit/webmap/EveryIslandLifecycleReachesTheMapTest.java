package com.uxplima.uxmskyblock.bukkit.webmap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island that changes reaches the map.
 *
 * <p>The synchroniser was written with three ways in and only one of them was called, so an island
 * made after a restart appeared and an island erased after one never went away. A web map that keeps
 * drawing an island nobody can visit is worse than a map with nothing on it, because the first is
 * believed.
 *
 * <p>Nothing else in the build can see this: an unused method compiles, and every test of the
 * synchroniser passes whether or not a caller ever reaches it.
 */
class EveryIslandLifecycleReachesTheMapTest {

    private static final Path LIFECYCLE =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/command/IslandLifecycleCommands.java");

    private static final Path BOOTSTRAP =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/SkyblockBootstrap.java");

    @Test
    @DisplayName("Creating, erasing and renaming an island each reach the map")
    void everyLifecycleStepReachesTheMap() throws IOException {
        String lifecycle = Files.readString(LIFECYCLE, StandardCharsets.UTF_8);

        assertThat(lifecycle).describedAs("a new island must be drawn").contains("onIslandCreated(");
        assertThat(lifecycle)
                .describedAs("an erased island must be taken off, or the map keeps drawing it")
                .contains("onIslandRemoved(");
        assertThat(lifecycle)
                .describedAs("a renamed island must be redrawn under its new name")
                .contains("onIslandChanged(");
    }

    @Test
    @DisplayName("Every island the world already holds is drawn when the server starts")
    void startupDrawsWhatIsAlreadyThere() throws IOException {
        assertThat(Files.readString(BOOTSTRAP, StandardCharsets.UTF_8))
                .describedAs("a map that only learns about islands made since the last restart "
                        + "has holes in it, and the holes are the oldest islands")
                .contains("drawAll(");
    }
}
