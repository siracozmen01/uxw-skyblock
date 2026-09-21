package com.uxplima.uxmskyblock.bukkit.booster;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mob death must not query the database.
 *
 * <p>{@code onEntityDeath} ran {@code getEffectiveMultiplier} on the event thread, which reads
 * {@code island_boosters} every time. On a region thread, under a mob farm, that is one query per
 * mob that dies. The standards call a database write on an event thread the worst defect in this
 * estate, and a read on a hot path is the same shape.
 *
 * <p>Nothing else in the build can see it: the query is three calls deep behind a service, the
 * types all line up, and the test that covers the boost passes either way because a test harness
 * has no region thread to stall.
 */
class BoosterListenerNeverQueriesOnDeathTest {

    private static final Path LISTENER =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/booster/IslandBoosterListener.java");

    private static String onEntityDeathBody() throws IOException {
        String source = Files.readString(LISTENER, StandardCharsets.UTF_8);
        int start = source.indexOf("public void onEntityDeath(");
        assertThat(start).describedAs("onEntityDeath must exist").isNotNegative();
        int end = source.indexOf("\n    }", start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("The death handler never reads the booster service directly")
    void deathHandlerDoesNotCallTheService() throws IOException {
        assertThat(onEntityDeathBody())
                .describedAs("a mob death must read a cached multiplier, never the service")
                .doesNotContain("boosterService.");
    }

    @Test
    @DisplayName("The death handler never resolves an island through storage")
    void deathHandlerDoesNotCallStorage() throws IOException {
        String body = onEntityDeathBody();
        assertThat(body).doesNotContain("islandStoragePort.");
        assertThat(body)
                .describedAs("resolving an island is a query, so it happens off this thread")
                .doesNotContain("findIslandIdForPlayer(");
    }

    @Test
    @DisplayName("The cached multiplier has an expiry an operator can set")
    void theCacheIsConfigurable() throws IOException {
        String source = Files.readString(LISTENER, StandardCharsets.UTF_8);
        assertThat(source)
                .describedAs("a stale multiplier must expire, and the operator names how fast")
                .contains("configuration.multiplierCacheTtl()");
    }
}
