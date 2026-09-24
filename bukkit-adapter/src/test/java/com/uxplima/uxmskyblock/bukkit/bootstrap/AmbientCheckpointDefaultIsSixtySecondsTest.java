package com.uxplima.uxmskyblock.bukkit.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A server that changes nothing checkpoints an online player's inventory once a minute.
 *
 * <p>The testing standard names this test. The shipped {@code config.conf} is read the way the plugin
 * reads it, the session subsystem is wired the way the plugin wires it, and a player joins. The
 * checkpoint the join schedules repeats every sixty seconds; the lease heartbeat beside it is a
 * different task at its own pace.
 */
class AmbientCheckpointDefaultIsSixtySecondsTest extends MockBukkitHarness {

    @Test
    @DisplayName("The shipped configuration checkpoints a joined player every sixty seconds")
    void theShippedConfigurationCheckpointsEveryMinute() throws Exception {
        assertThat(CheckpointCadence.shipped().ambientCheckpointInterval()).isEqualTo(Duration.ofSeconds(60));

        Path dir = Files.createTempDirectory("cadence_");
        try (CheckpointCadence cadence = new CheckpointCadence(
                MockBukkit.createMockPlugin(), dir.resolve("s.db"), CheckpointCadence.shipped())) {
            PlayerMock player = createPlayer("Cadence");
            cadence.wiring.sessionCoordinator().handlePlayerJoin(player);

            eventually(() -> assertThat(cadence.periods)
                    .containsExactlyInAnyOrder(CheckpointCadence.HEARTBEAT, Duration.ofSeconds(60)));
        }
    }
}
