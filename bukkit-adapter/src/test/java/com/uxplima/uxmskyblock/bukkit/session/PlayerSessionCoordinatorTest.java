package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.scheduler.FoliaSchedulerAdapter;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
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

class PlayerSessionCoordinatorTest extends MockBukkitHarness {

    private Path tempDir;
    private PersistenceBootstrap persistenceBootstrap;
    private FoliaSchedulerAdapter scheduler;
    private IslandProtectionListener protectionListener;
    private SwitchProfileUseCase switchProfileUseCase;
    private PlayerSessionCoordinator coordinator;
    private final ServerNodeId nodeId = new ServerNodeId("test-node-1");

    @BeforeEach
    void setUpCoordinator() throws IOException {
        tempDir = Files.createTempDirectory("session_test_");
        Path dbPath = tempDir.resolve("test.db");
        persistenceBootstrap = PersistenceBootstrap.createSqlite(dbPath);

        // create a dummy plugin
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        scheduler = new FoliaSchedulerAdapter(plugin);
        protectionListener =
                new IslandProtectionListener(persistenceBootstrap.islandStoragePort(), new IslandAccessService());
        switchProfileUseCase = new SwitchProfileUseCase(
                persistenceBootstrap.profileSwitchPort(), persistenceBootstrap.inventoryPort());

        coordinator = new PlayerSessionCoordinator(
                nodeId,
                persistenceBootstrap.sessionAuthorityPort(),
                persistenceBootstrap.inventoryPort(),
                persistenceBootstrap.handoffFinalizationPort(),
                switchProfileUseCase,
                scheduler,
                protectionListener,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDownCoordinator() {
        if (coordinator != null) {
            coordinator.shutdown();
        }
        if (persistenceBootstrap != null) {
            persistenceBootstrap.close();
        }
    }

    @Test
    @DisplayName("handlePlayerJoin bootstraps session authority and loads inventory")
    void playerJoinBootstrapsSession() {
        PlayerMock player = createPlayer("JoinPlayer");
        coordinator.handlePlayerJoin(player);

        eventually(() -> {
            PlayerSessionCoordinator.ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
            assertThat(session).isNotNull();
            assertThat(session.sessionEpoch()).isEqualTo(1L);
            assertThat(session.lastDurableVersion()).isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    @DisplayName("checkpointPlayer saves updated player inventory snapshot")
    void checkpointSavesInventory() {
        PlayerMock player = createPlayer("CheckpointPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 10));
        coordinator.checkpointPlayer(new PlayerUuid(player.getUniqueId()));

        eventuallyTick(() -> {
            PlayerSessionCoordinator.ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
            assertThat(session).isNotNull();
            Optional<ProfileInventoryRecord> inv =
                    persistenceBootstrap.inventoryPort().loadInventory(session.activeProfileId());
            assertThat(inv).isPresent();
            byte[] nbt = inv.get().inventoryNbt();
            assertThat(nbt).isNotEmpty();
            ItemStack[] items = BukkitInventorySerializer.deserializeItemStacks(nbt);
            assertThat(items).isNotEmpty();
            assertThat(items[0]).isEqualTo(new ItemStack(Material.DIAMOND, 10));
        });
    }

    @Test
    @DisplayName("handlePlayerQuit finalizes handoff and flushes durable inventory")
    void playerQuitDrainsAndFlushes() {
        PlayerMock player = createPlayer("QuitPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 3));
        coordinator.handlePlayerQuit(player);

        assertThat(coordinator.getActiveSession(player.getUniqueId())).isNull();

        eventuallyTick(() -> {
            ProfileId profileId = new ProfileId(player.getUniqueId());
            Optional<ProfileInventoryRecord> inv =
                    persistenceBootstrap.inventoryPort().loadInventory(profileId);
            assertThat(inv).isPresent();
            byte[] nbt = inv.get().inventoryNbt();
            assertThat(nbt).isNotEmpty();
            ItemStack[] items = BukkitInventorySerializer.deserializeItemStacks(nbt);
            assertThat(items).isNotEmpty();
            assertThat(items[0]).isEqualTo(new ItemStack(Material.GOLD_BLOCK, 3));
        });
    }

    @Test
    @DisplayName("switchProfile transitions player items between profiles")
    void switchProfileTransfersInventory() {
        PlayerMock player = createPlayer("SwitchPlayer");
        coordinator.handlePlayerJoin(player);

        eventuallyTick(() ->
                assertThat(coordinator.getActiveSession(player.getUniqueId())).isNotNull());

        PlayerSessionCoordinator.ActiveSession session = coordinator.getActiveSession(player.getUniqueId());
        assertThat(session).isNotNull();
        ProfileId profileA = session.activeProfileId();
        ProfileId profileB = new ProfileId(UUID.randomUUID());
        assertThat(profileA).isNotEqualTo(profileB);

        // Pre-seed profile B in DB
        persistenceBootstrap.registerProfile(new PlayerUuid(player.getUniqueId()), profileB);

        ItemStack[] bItems = new ItemStack[41];
        bItems[0] = new ItemStack(Material.EMERALD, 64);
        byte[] bNbt = BukkitInventorySerializer.serializeItemStacks(bItems);
        persistenceBootstrap
                .inventoryPort()
                .initializeInventory(ProfileInventoryRecord.createDefault(profileB, bNbt, new byte[0]));

        // Give player netherite on profile A
        player.getInventory().setItem(0, new ItemStack(Material.NETHERITE_INGOT, 5));

        coordinator.switchProfile(player, profileB);

        eventuallyTick(() -> {
            PlayerSessionCoordinator.ActiveSession currentSession = coordinator.getActiveSession(player.getUniqueId());
            assertThat(currentSession).isNotNull();
            assertThat(currentSession.activeProfileId()).isEqualTo(profileB);
            assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.EMERALD, 64));
        });
    }

    private void eventuallyTick(Runnable assertion) {
        long start = System.currentTimeMillis();
        AssertionError last = null;
        while (System.currentTimeMillis() - start < 5000) {
            try {
                if (server != null && server.getScheduler() != null) {
                    server.getScheduler().performOneTick();
                }
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        if (last != null) {
            throw last;
        }
    }
}
