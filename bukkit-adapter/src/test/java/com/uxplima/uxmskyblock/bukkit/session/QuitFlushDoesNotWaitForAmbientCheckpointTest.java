package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A quit writes what the player holds at once, whatever the ambient checkpoint's schedule.
 *
 * <p>The testing standard names this test. The checkpoint here runs once an hour; the quit's own
 * final write is what puts the player's state in the database and releases the session.
 */
class QuitFlushDoesNotWaitForAmbientCheckpointTest extends MockBukkitHarness {

    private SessionBench bench;

    @BeforeEach
    void setUpBench() throws Exception {
        bench = new SessionBench(server);
    }

    @AfterEach
    void tearDownBench() {
        bench.close();
    }

    @Test
    @DisplayName("A quit writes the player's state and releases the session without waiting for a checkpoint")
    void theQuitWritesAtOnce() {
        PlayerMock player = bench.inPlay(createPlayer("Leaver"));
        long versionAtJoin = bench.stored(player).version();
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 6));
        player.setLevel(4);

        bench.coordinator.handlePlayerQuit(player);

        bench.until(() -> assertThat(bench.persistence
                        .sessionAuthorityPort()
                        .findSession(new PlayerUuid(player.getUniqueId()))
                        .orElseThrow()
                        .state())
                .isEqualTo(SessionState.OFFLINE));
        assertThat(bench.stored(player).version()).isEqualTo(versionAtJoin + 1);
        assertThat(SessionBench.items(bench.stored(player))).containsExactly(new ItemStack(Material.GOLD_BLOCK, 6));
        assertThat(bench.stored(player).experiencePoints())
                .describedAs("four levels")
                .isEqualTo(40);
    }
}
