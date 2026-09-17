package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"deprecation", "removal"})
class IslandProtectionListenerTest extends MockBukkitHarness {

    private World world;
    private IslandProtectionListener listener;
    private Island island;
    private PlayerMock ownerPlayer;
    private PlayerMock visitorPlayer;
    private PlayerUuid ownerUuid;
    private ProfileId ownerProfileId;
    private PlayerUuid visitorUuid;
    private ProfileId visitorProfileId;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("test_world");
        IslandAccessService accessService = new IslandAccessService();
        IslandStoragePort dummyStorage = new IslandStoragePort() {
            @Override
            public void saveIsland(Island island, IslandLocation location) {}

            @Override
            public Optional<Island> findIslandById(IslandId id) {
                return Optional.empty();
            }

            @Override
            public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
                return Optional.empty();
            }

            @Override
            public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
                return Optional.empty();
            }

            @Override
            public void deleteIsland(IslandId id) {}
        };

        listener = new IslandProtectionListener(dummyStorage, accessService);

        ownerPlayer = createPlayer("OwnerPlayer");
        visitorPlayer = createPlayer("VisitorPlayer");

        ownerUuid = new PlayerUuid(ownerPlayer.getUniqueId());
        ownerProfileId = new ProfileId(ownerPlayer.getUniqueId());
        visitorUuid = new PlayerUuid(visitorPlayer.getUniqueId());
        visitorProfileId = new ProfileId(visitorPlayer.getUniqueId());

        listener.setActiveProfile(ownerUuid, ownerProfileId);
        listener.setActiveProfile(visitorUuid, visitorProfileId);

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(50, 50, 50);
        island = Island.create(IslandId.of(UUID.randomUUID()), bounds, ownerUuid, ownerProfileId, Instant.now());
        listener.cacheIsland(island);
    }

    @Test
    @DisplayName("owner can break blocks on their island")
    void ownerCanBreakBlocks() {
        Block block = world.getBlockAt(50, 64, 50);
        block.setType(Material.STONE);

        BlockBreakEvent event = new BlockBreakEvent(block, ownerPlayer);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("non-member cannot break blocks on an island")
    void visitorCannotBreakBlocks() {
        Block block = world.getBlockAt(50, 64, 50);
        block.setType(Material.STONE);

        BlockBreakEvent event = new BlockBreakEvent(block, visitorPlayer);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("admin with bypass permission can break blocks anywhere")
    void adminCanBypassBlockBreak() {
        visitorPlayer.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), "uxmskyblock.admin.bypass", true);
        Block block = world.getBlockAt(50, 64, 50);
        block.setType(Material.STONE);

        BlockBreakEvent event = new BlockBreakEvent(block, visitorPlayer);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("block break outside any island boundary is permitted")
    void breakOutsideIslandIsAllowed() {
        Block block = world.getBlockAt(500, 64, 500);
        block.setType(Material.STONE);

        BlockBreakEvent event = new BlockBreakEvent(block, visitorPlayer);
        listener.onBlockBreak(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("visitor cannot place blocks on an island")
    void visitorCannotPlaceBlocks() {
        Block block = world.getBlockAt(50, 64, 50);
        Block placedAgainst = world.getBlockAt(50, 63, 50);

        BlockPlaceEvent event = new BlockPlaceEvent(
                block,
                block.getState(),
                placedAgainst,
                new ItemStack(Material.COBBLESTONE),
                visitorPlayer,
                true,
                EquipmentSlot.HAND);
        listener.onBlockPlace(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("visitor cannot interact with blocks on an island")
    void visitorCannotInteractWithBlocks() {
        Block block = world.getBlockAt(50, 64, 50);
        PlayerInteractEvent event = new PlayerInteractEvent(
                visitorPlayer,
                Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.STICK),
                block,
                org.bukkit.block.BlockFace.UP);
        listener.onPlayerInteract(event);

        assertThat(event.useInteractedBlock()).isEqualTo(Event.Result.DENY);
    }

    @Test
    @DisplayName("pvp damage is cancelled when pvp flag is disabled")
    void pvpDamageCancelledWhenPvpDisabled() {
        ownerPlayer.teleport(new Location(world, 50, 64, 50));
        visitorPlayer.teleport(new Location(world, 50, 64, 50));

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                visitorPlayer, ownerPlayer, EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK, 4.0);
        listener.onEntityDamage(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("pvp damage is allowed when pvp flag is enabled")
    void pvpDamageAllowedWhenPvpEnabled() {
        Island pvpIsland = island.withFlags(island.flags().withFlag(IslandFlags.PVP, true));
        listener.cacheIsland(pvpIsland);

        ownerPlayer.teleport(new Location(world, 50, 64, 50));
        visitorPlayer.teleport(new Location(world, 50, 64, 50));

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                visitorPlayer, ownerPlayer, EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK, 4.0);
        listener.onEntityDamage(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
