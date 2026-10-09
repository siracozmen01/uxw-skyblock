package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class IslandControlMenuTest extends MockBukkitHarness {

    private IslandStoragePort mockStorage;
    private IslandBankPort mockBank;
    private IslandUpgradeStoragePort mockUpgrades;
    private IslandLocationService mockLocations;
    private DirectSchedulerPort scheduler;

    private IslandControlMenu menu;
    private Player player;
    private IslandId islandId;
    private Island sampleIsland;

    @BeforeEach
    void setUpMenu() {
        Guis.install(MockBukkit.createMockPlugin());

        mockStorage = mock(IslandStoragePort.class);
        mockBank = mock(IslandBankPort.class);
        mockUpgrades = mock(IslandUpgradeStoragePort.class);
        mockLocations = mock(IslandLocationService.class);
        scheduler = new DirectSchedulerPort();

        menu = new IslandControlMenu(
                mockStorage,
                mockBank,
                mockUpgrades,
                mockLocations,
                scheduler,
                "skyblock_world",
                uuid -> Optional.of(new ProfileId(uuid)),
                Messages.bundled());

        player = createPlayer("TestPlayer");
        islandId = new IslandId(UUID.randomUUID());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        sampleIsland = Island.create(
                islandId,
                bounds,
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
    }

    @Test
    @DisplayName("buildGui populates expected icons in correct slots")
    void buildGuiPopulatesExpectedSlots() {
        IslandBank bank = new IslandBank(islandId, 25000L, 10L, 50L, 1L, Instant.now());
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        IslandLocation loc = new IslandLocation(islandId, "skyblock_world", bounds, 0, 70, 0, 0f, 0f);
        Map<UpgradeId, Integer> upgrades = Map.of(UpgradeId.SIZE, 2, UpgradeId.SPAWNER_RATES, 3);

        SimpleGui gui = menu.buildGui(player, sampleIsland, bank, upgrades, Optional.of(loc));

        assertThat(gui.size()).isEqualTo(36);
        assertThat(gui.getItem(10)).isNotNull(); // Overview
        assertThat(gui.getItem(11)).isNotNull(); // Bank
        assertThat(gui.getItem(12)).isNotNull(); // Upgrades
        assertThat(gui.getItem(13)).isNotNull(); // Biome
        assertThat(gui.getItem(14)).isNotNull(); // Members
        assertThat(gui.getItem(15)).isNotNull(); // Settings
        assertThat(gui.getItem(16)).isNotNull(); // Teleport Home
        gui.open(player);
        org.bukkit.inventory.ItemStack free = java.util.Objects.requireNonNull(
                player.getOpenInventory().getTopInventory().getItem(31));
        assertThat(free.getType())
                .describedAs("a window has no close button: Escape closes it, and a free slot is the blank pane")
                .isEqualTo(org.bukkit.Material.GRAY_STAINED_GLASS_PANE);
        org.bukkit.inventory.ItemStack upgradesTile = java.util.Objects.requireNonNull(
                player.getOpenInventory().getTopInventory().getItem(12));
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(upgradesTile.lore())
                                .get(0)))
                .describedAs("a tile's name is blank and its title opens the lore")
                .contains("Island upgrades");
    }

    @Test
    @DisplayName("A refresh draws the upgrades window again with the tier bought, and opens nothing the player left")
    void aRefreshDrawsTheTierBought(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dataDir) {
        ProfileId profileId = new ProfileId(player.getUniqueId());
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));
        when(mockStorage.findIslandById(eq(islandId))).thenReturn(Optional.of(sampleIsland));
        when(mockBank.findBankByIslandId(eq(islandId))).thenReturn(Optional.empty());
        when(mockUpgrades.getUpgrades(eq(islandId))).thenReturn(Map.of());
        when(mockLocations.resolveHome(eq(profileId))).thenReturn(Optional.empty());
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-upgrades.conf");
        engine.install();
        menu.useMenuEngine(engine);
        engine.open(player, IslandControlMenu.UPGRADES, Map.of("size_tier", "0"));
        settle(() -> engine.showing(player, IslandControlMenu.UPGRADES));
        assertThat(tierOnTheSizeTile()).isEqualTo("0");

        when(mockUpgrades.getUpgrades(eq(islandId))).thenReturn(Map.of(UpgradeId.SIZE, 2));
        menu.refresh(player, IslandControlMenu.UPGRADES);
        settle(() -> "2".equals(tierOnTheSizeTile()));

        player.closeInventory();
        menu.refresh(player, IslandControlMenu.UPGRADES);
        for (int tick = 0; tick < 5; tick++) {
            server.getScheduler().performOneTick();
            server.getScheduler().waitAsyncTasksFinished();
        }
        assertThat(engine.showing(player, IslandControlMenu.UPGRADES)).isFalse();
    }

    /** The tier the size tile of the open window reads, or nothing when there is no such line. */
    private @org.jspecify.annotations.Nullable String tierOnTheSizeTile() {
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        org.bukkit.inventory.ItemStack tile = top == null ? null : top.getItem(11);
        if (tile == null || tile.lore() == null) {
            return null;
        }
        return java.util.Objects.requireNonNull(tile.lore()).stream()
                .map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize)
                .filter(line -> line.contains("Tier"))
                .map(line -> line.strip().substring(line.strip().lastIndexOf(' ') + 1))
                .findFirst()
                .orElse(null);
    }

    private void settle(java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            if (done.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the window never settled, the size tile reads tier " + tierOnTheSizeTile());
    }

    @Test
    @DisplayName("open warns player when no island exists")
    void openWarnsPlayerWithoutIsland() {
        when(mockStorage.findIslandIdByProfileId(eq(new ProfileId(player.getUniqueId()))))
                .thenReturn(Optional.empty());

        menu.open(player);
        // Player should have received a warning and no 36-slot control panel opened
        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
            assertThat(player.getOpenInventory().getTopInventory().getSize()).isNotEqualTo(36);
        }
    }

    @Test
    @DisplayName("open displays control panel when player has island")
    void openDisplaysControlPanel() {
        ProfileId profileId = new ProfileId(player.getUniqueId());
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));
        when(mockStorage.findIslandById(eq(islandId))).thenReturn(Optional.of(sampleIsland));
        when(mockBank.findBankByIslandId(eq(islandId))).thenReturn(Optional.empty());
        when(mockUpgrades.getUpgrades(eq(islandId))).thenReturn(Map.of());
        when(mockLocations.resolveHome(eq(profileId))).thenReturn(Optional.empty());

        menu.open(player);

        assertThat(player.getOpenInventory().getTopInventory().getSize()).isEqualTo(36);
    }

    @Test
    @DisplayName("An island on its free base size tier reads that tier on the panel, as the upgrade list does")
    void theFreeBaseTierReadsAsHeld() {
        ProfileId profileId = new ProfileId(player.getUniqueId());
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));
        when(mockStorage.findIslandById(eq(islandId))).thenReturn(Optional.of(sampleIsland));
        when(mockBank.findBankByIslandId(eq(islandId))).thenReturn(Optional.empty());
        when(mockUpgrades.getUpgrades(eq(islandId))).thenReturn(Map.of());
        when(mockLocations.resolveHome(eq(profileId))).thenReturn(Optional.empty());
        menu.useUpgradeStanding(new com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService(
                mockUpgrades,
                com.uxplima.uxmskyblock.bukkit.config.UpgradesConfiguration.defaultConfiguration()
                        .definitions())::standing);

        menu.open(player);

        org.bukkit.inventory.ItemStack upgrades = java.util.Objects.requireNonNull(
                player.getOpenInventory().getTopInventory().getItem(12));
        String lore = java.util.Objects.requireNonNull(upgrades.lore()).stream()
                .map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(lore).contains("Size tier 1");
    }

    @Test
    @DisplayName("open delegates to BedrockFormService when player is on Bedrock")
    void openDelegatesToBedrockFormServiceWhenBedrockPlayer() {
        com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService mockBedrock =
                mock(com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService.class);
        when(mockBedrock.isBedrock(player)).thenReturn(true);

        IslandControlMenu bedrockMenu = new IslandControlMenu(
                mockStorage,
                mockBank,
                mockUpgrades,
                mockLocations,
                scheduler,
                "skyblock_world",
                uuid -> Optional.of(new ProfileId(uuid)),
                mockBedrock,
                Messages.bundled());

        ProfileId profileId = new ProfileId(player.getUniqueId());
        when(mockStorage.findIslandIdByProfileId(eq(profileId))).thenReturn(Optional.of(islandId));
        when(mockStorage.findIslandById(eq(islandId))).thenReturn(Optional.of(sampleIsland));

        bedrockMenu.open(player);

        org.mockito.Mockito.verify(mockBedrock)
                .openIslandControlForm(
                        eq(player),
                        eq(sampleIsland),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private static class DirectSchedulerPort implements SchedulerPort {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    }
}
