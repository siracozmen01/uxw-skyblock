package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A profile switch keeps what the player had on the profile they leave.
 *
 * <p>The switch kept the leaving profile's inventory on the operation's own row and never wrote it to
 * the profile. The profile stayed as the last checkpoint left it, so a player who switched away and
 * back lost everything gained since, and every switch lost the ender chest and experience outright.
 * The leaving profile is now checkpointed, whole, before the switch goes ahead.
 */
class ASwitchKeepsTheProfileItLeavesTest extends MockBukkitHarness {

    private PersistenceBootstrap persistence;
    private PlayerSessionCoordinator coordinator;

    @BeforeEach
    void setUpCoordinator() throws Exception {
        Path dir = Files.createTempDirectory("switch_");
        persistence = PersistenceBootstrap.createSqlite(dir.resolve("switch.db"));
        coordinator = new PlayerSessionCoordinator(
                ServerNodeId.of("switch-node"),
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
    @DisplayName("Switching away and back finds the leaving profile as it was left")
    void awayAndBack() {
        PlayerMock player = createPlayer("Switcher");
        coordinator.handlePlayerJoin(player);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull();
        });
        ProfileId first = Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()))
                .activeProfileId();
        ProfileId second = new ProfileId(UUID.randomUUID());
        persistence.registerProfile(new PlayerUuid(player.getUniqueId()), second);
        persistence
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(second, new byte[0], new byte[0]));

        player.getInventory().setItem(0, new ItemStack(Material.NETHERITE_INGOT, 5));
        player.getEnderChest().setItem(2, new ItemStack(Material.EMERALD, 9));
        player.setLevel(12);

        switchTo(player, second);

        ProfileInventoryRecord left =
                persistence.inventoryPort().loadInventory(first).orElseThrow();
        assertThat(items(left.inventoryNbt())).containsExactly(new ItemStack(Material.NETHERITE_INGOT, 5));
        assertThat(items(left.enderchestNbt())).containsExactly(new ItemStack(Material.EMERALD, 9));
        assertThat(left.experiencePoints()).describedAs("twelve levels").isEqualTo(216);

        switchTo(player, first);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.NETHERITE_INGOT, 5));
        assertThat(player.getEnderChest().getItem(2)).isEqualTo(new ItemStack(Material.EMERALD, 9));
        assertThat(player.getLevel()).isEqualTo(12);
    }

    private void switchTo(PlayerMock player, ProfileId profile) {
        coordinator.switchProfile(player, profile);
        eventually(() -> {
            server.getScheduler().performOneTick();
            assertThat(Objects.requireNonNull(coordinator.getActiveSession(player.getUniqueId()))
                            .activeProfileId())
                    .isEqualTo(profile);
        });
    }

    private static ItemStack[] items(byte[] nbt) {
        return Arrays.stream(BukkitInventorySerializer.deserializeItemStacks(nbt))
                .filter(Objects::nonNull)
                .filter(item -> !item.getType().isAir())
                .toArray(ItemStack[]::new);
    }
}
