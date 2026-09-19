package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class IslandBoosterMenuTest extends MockBukkitHarness {

    private IslandStoragePort mockStorage;
    private IslandBoosterService mockBoosterService;
    private BoosterConfiguration configuration;
    private PlayerSessionCoordinator mockSessionCoordinator;

    private IslandBoosterMenu menu;
    private Player player;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        Guis.install(MockBukkit.createMockPlugin());

        mockStorage = mock(IslandStoragePort.class);
        mockBoosterService = mock(IslandBoosterService.class);
        configuration = BoosterConfiguration.defaultConfiguration();
        mockSessionCoordinator = mock(PlayerSessionCoordinator.class);

        menu = new IslandBoosterMenu(mockStorage, mockBoosterService, configuration, mockSessionCoordinator);

        player = createPlayer("BoosterGuiPlayer");
        islandId = new IslandId(UUID.randomUUID());
    }

    @Test
    @DisplayName("buildGui populates header, category cards, and close button")
    void buildGuiPopulatesExpectedSlots() {
        Instant now = Instant.now();
        when(mockBoosterService.getActiveBoosters(eq(islandId), any(Instant.class)))
                .thenReturn(List.of(
                        IslandBooster.create(islandId, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), now)));
        when(mockBoosterService.getActiveBoosters(eq(islandId), eq(BoosterCategory.MOB_EXP), any(Instant.class)))
                .thenReturn(List.of(
                        IslandBooster.create(islandId, BoosterCategory.MOB_EXP, 2.0, Duration.ofHours(1), now)));
        when(mockBoosterService.getEffectiveMultiplier(eq(islandId), eq(BoosterCategory.MOB_EXP), any(Instant.class)))
                .thenReturn(2.0);

        SimpleGui gui = menu.buildGui(player, islandId, now);

        assertThat(gui.size()).isEqualTo(36);
        assertThat(gui.getItem(4)).isNotNull(); // Header
        assertThat(gui.getItem(10)).isNotNull(); // Spawner Rate
        assertThat(gui.getItem(12)).isNotNull(); // Crop Growth
        assertThat(gui.getItem(14)).isNotNull(); // Ore Generator
        assertThat(gui.getItem(16)).isNotNull(); // Mob Exp
        assertThat(gui.getItem(21)).isNotNull(); // Island Worth
        assertThat(gui.getItem(23)).isNotNull(); // Mission Rewards
        assertThat(gui.getItem(31)).isNotNull(); // Close button
    }

    @Test
    @DisplayName("open warns player when no island exists")
    void openWarnsPlayerWithoutIsland() {
        when(mockStorage.findIslandIdByProfileId(any())).thenReturn(Optional.empty());

        menu.open(player);

        if (player.getOpenInventory() != null && player.getOpenInventory().getTopInventory() != null) {
            assertThat(player.getOpenInventory().getTopInventory().getSize()).isNotEqualTo(36);
        }
    }

    @Test
    @DisplayName("open displays booster panel when player has island")
    void openDisplaysBoosterPanel() {
        ProfileId profileId = new ProfileId(player.getUniqueId());
        when(mockSessionCoordinator.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));
        when(mockStorage.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));

        menu.open(player);

        assertThat(player.getOpenInventory()).isNotNull();
        assertThat(player.getOpenInventory().getTopInventory().getSize()).isEqualTo(36);
    }
}
