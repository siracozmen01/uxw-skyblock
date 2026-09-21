package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shutdown waits for the writes it handed out before it closes what they write to.
 *
 * <p>The plugin closed its connection pool while async work was still running against it. The
 * symptom is a flaky test and a stack trace at shutdown; the cost on a live server is an island
 * change that was accepted, reported to the player, and never landed.
 *
 * <p>Nothing else in the build can see this. The order of two statements in one method compiles
 * either way and every test passes either way, because a test that shuts down cleanly has no
 * pending work to lose.
 */
class ShutdownDrainsBeforeClosingTest {

    private static final Path BOOTSTRAP =
            Path.of("src/main/java/com/uxplima/uxmskyblock/bukkit/bootstrap/SkyblockBootstrap.java");

    @Test
    @DisplayName("The shutdown drains async work before it closes the connection pool")
    void drainComesBeforeClose() throws IOException {
        String source = Files.readString(BOOTSTRAP, StandardCharsets.UTF_8);

        int drain = source.indexOf("drainAsync(");
        int close = source.indexOf("persistenceWiring.close()");

        assertThat(drain)
                .describedAs("SkyblockBootstrap must drain async work at shutdown")
                .isNotNegative();
        assertThat(close)
                .describedAs("SkyblockBootstrap must close the persistence layer at shutdown")
                .isNotNegative();
        assertThat(drain)
                .describedAs("the drain must come before the pool closes, or the drain is decoration")
                .isLessThan(close);
    }

    @Test
    @DisplayName("A drain that runs out of time is reported, never swallowed")
    void aTimedOutDrainIsLogged() throws IOException {
        String source = Files.readString(BOOTSTRAP, StandardCharsets.UTF_8);

        int drain = source.indexOf("drainAsync(");
        String afterDrain = source.substring(drain, Math.min(source.length(), drain + 600));

        assertThat(afterDrain)
                .describedAs("an operator whose server lost a write at shutdown must find it in the log")
                .contains("warning(");
    }
}
