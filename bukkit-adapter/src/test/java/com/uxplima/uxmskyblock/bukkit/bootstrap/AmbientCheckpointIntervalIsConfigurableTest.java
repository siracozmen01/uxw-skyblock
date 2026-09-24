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
 * The ambient checkpoint runs at the interval the operator wrote, and nowhere is a number fixed.
 *
 * <p>The testing standard names this test. An operator writes an interval nobody would pick as a
 * default, the session subsystem is wired from it, and a player joins: the checkpoint repeats at that
 * interval.
 */
class AmbientCheckpointIntervalIsConfigurableTest extends MockBukkitHarness {

    @Test
    @DisplayName("A joined player is checkpointed at the interval the operator wrote")
    void theWrittenIntervalIsTheCadence() throws Exception {
        var written = CheckpointCadence.written("""
                player-state {
                    ambient-checkpoint-interval = "17s"
                }
                """);

        Path dir = Files.createTempDirectory("cadence_");
        try (CheckpointCadence cadence =
                new CheckpointCadence(MockBukkit.createMockPlugin(), dir.resolve("s.db"), written)) {
            PlayerMock player = createPlayer("Cadence");
            cadence.wiring.sessionCoordinator().handlePlayerJoin(player);

            eventually(() -> assertThat(cadence.periods)
                    .containsExactlyInAnyOrder(CheckpointCadence.HEARTBEAT, Duration.ofSeconds(17)));
        }
    }
}
