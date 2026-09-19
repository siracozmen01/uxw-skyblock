package com.uxplima.uxmskyblock.bukkit.booster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandBoosterListenerTest extends MockBukkitHarness {

    private IslandStoragePort mockStoragePort;
    private IslandBoosterService mockBoosterService;
    private BoosterConfiguration configuration;
    private PlayerSessionCoordinator mockSessionCoordinator;
    private IslandBoosterListener listener;

    private Instant fixedNow;
    private IslandId islandId;
    private Island sampleIsland;
    private Player testPlayer;

    @BeforeEach
    void setUp() {
        mockStoragePort = mock(IslandStoragePort.class);
        mockBoosterService = mock(IslandBoosterService.class);
        configuration = BoosterConfiguration.defaultConfiguration();
        mockSessionCoordinator = mock(PlayerSessionCoordinator.class);

        fixedNow = Instant.parse("2026-09-19T12:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));

        listener = new IslandBoosterListener(
                mockStoragePort, mockBoosterService, configuration, mockSessionCoordinator, fixedClock);

        testPlayer = createPlayer("BoosterTester");
        islandId = new IslandId(UUID.randomUUID());
        ProfileId profileId = new ProfileId(testPlayer.getUniqueId());

        sampleIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(testPlayer.getUniqueId()),
                profileId,
                Instant.now());

        when(mockSessionCoordinator.activeProfile(testPlayer.getUniqueId())).thenReturn(Optional.of(profileId));
        when(mockStoragePort.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        when(mockStoragePort.findIslandById(islandId)).thenReturn(Optional.of(sampleIsland));
    }

    @Test
    @DisplayName("Resumes island boosters when first member joins")
    void resumesBoostersOnFirstMemberJoin() {
        PlayerJoinEvent event = new PlayerJoinEvent(testPlayer, net.kyori.adventure.text.Component.empty());
        listener.onPlayerJoin(event);

        verify(mockBoosterService).resumeBoosters(islandId, fixedNow);
    }

    @Test
    @DisplayName("Pauses island boosters when last member leaves")
    void pausesBoostersOnLastMemberQuit() {
        PlayerQuitEvent event = new PlayerQuitEvent(
                testPlayer, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED);
        listener.onPlayerQuit(event);

        verify(mockBoosterService).pauseBoosters(islandId, fixedNow);
    }

    @Test
    @DisplayName("Multiplies dropped exp on entity death when MOB_EXP booster is active")
    void multipliesExpOnEntityDeath() {
        when(mockBoosterService.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, fixedNow))
                .thenReturn(2.5);

        World world = server.addSimpleWorld("skyblock_world");
        LivingEntity entity = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);

        DamageSource damageSource = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(testPlayer)
                .withDirectEntity(testPlayer)
                .build();
        List<ItemStack> drops = new ArrayList<>();
        EntityDeathEvent event = new EntityDeathEvent(entity, damageSource, drops, 20);

        listener.onEntityDeath(event);

        assertThat(event.getDroppedExp()).isEqualTo(50); // 20 * 2.5 = 50
    }

    @Test
    @DisplayName("Does not modify dropped exp when multiplier is 1.0")
    void doesNotModifyExpWhenMultiplierIsOne() {
        when(mockBoosterService.getEffectiveMultiplier(islandId, BoosterCategory.MOB_EXP, fixedNow))
                .thenReturn(1.0);

        World world = server.addSimpleWorld("skyblock_world");
        LivingEntity entity = (LivingEntity) world.spawnEntity(new Location(world, 0, 64, 0), EntityType.ZOMBIE);

        DamageSource damageSource = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(testPlayer)
                .withDirectEntity(testPlayer)
                .build();
        List<ItemStack> drops = new ArrayList<>();
        EntityDeathEvent event = new EntityDeathEvent(entity, damageSource, drops, 20);

        listener.onEntityDeath(event);

        assertThat(event.getDroppedExp()).isEqualTo(20);
    }
}
