package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A player who leaves and comes back has what they left with: not only the inventory.
 *
 * <p>The checkpoint and the final write of a session wrote the inventory and nothing else, and a join
 * put every column back. The ender chest, experience, health and hunger came back as they were when
 * the profile was made. Seen on the lab server: thirty levels given by a command were none after a
 * rejoin. Every write of a player's state now writes all of it, and experience is counted from the
 * level and the bar rather than from a total that a command never touches.
 */
class APlayerComesBackWithWhatTheyCarriedTest extends MockBukkitHarness {

    private PersistenceBootstrap persistence;
    private PlayerSessionCoordinator coordinator;

    @BeforeEach
    void setUpCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("carried_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("carried.db"));
        coordinator = new PlayerSessionCoordinator(
                ServerNodeId.of("carried-node"),
                persistence.sessionAuthorityPort(),
                persistence.inventoryPort(),
                persistence.handoffFinalizationPort(),
                new SwitchProfileUseCase(persistence.profileSwitchPort(), persistence.inventoryPort()),
                new InventoryJournalRecovery(persistence.mutationJournalPort(), persistence.inventoryPort()),
                new FoliaSchedulerAdapter(MockBukkit.createMockPlugin()),
                new IslandProtectionListener(persistence.islandStoragePort(), new IslandAccessService()),
                Duration.ofHours(1),
                Duration.ofHours(1),
                Messages.bundled());
    }

    @AfterEach
    void tearDownCoordinator() {
        coordinator.shutdown();
        persistence.close();
    }

    @Test
    @DisplayName("A rejoin gives back the ender chest, the levels, health, hunger and game mode")
    void aRejoinGivesBackEverything() {
        PlayerMock player = createPlayer("Carrier");
        joined(player, 1);
        player.getEnderChest().setItem(4, new ItemStack(Material.EMERALD, 9));
        player.setLevel(30);
        player.setExp(0.5f);
        player.setHealth(7.0);
        player.setFoodLevel(5);
        player.setGameMode(GameMode.ADVENTURE);

        coordinator.handlePlayerQuit(player);
        eventually(() -> assertThat(persistence
                        .sessionAuthorityPort()
                        .findSession(new PlayerUuid(player.getUniqueId()))
                        .orElseThrow()
                        .state())
                .isEqualTo(SessionState.OFFLINE));

        // Whatever the server keeps of its own is gone; only what the plugin wrote comes back.
        player.getEnderChest().clear();
        player.setLevel(0);
        player.setExp(0);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setGameMode(GameMode.SURVIVAL);

        joined(player, 2);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(player.getEnderChest().getItem(4)).isEqualTo(new ItemStack(Material.EMERALD, 9));
            assertThat(player.getLevel()).isEqualTo(30);
            assertThat(player.getExp()).isCloseTo(0.5f, within(0.05f));
            assertThat(player.getHealth()).isEqualTo(7.0);
            assertThat(player.getFoodLevel()).isEqualTo(5);
            assertThat(player.getGameMode()).isEqualTo(GameMode.ADVENTURE);
        });
    }

    private void joined(PlayerMock player, long epoch) {
        coordinator.handlePlayerJoin(player);
        eventually(() -> {
            server.getScheduler().performOneTick();
            ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
            assertThat(session).isNotNull();
            assertThat(java.util.Objects.requireNonNull(session).sessionEpoch()).isEqualTo(epoch);
        });
    }
}
