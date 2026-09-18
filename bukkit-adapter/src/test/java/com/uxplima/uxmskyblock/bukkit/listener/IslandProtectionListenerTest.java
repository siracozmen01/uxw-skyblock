package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
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

import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
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

    @Test
    @DisplayName("loadPersistedIslands populates in-memory cache from storage")
    void loadPersistedIslandsPopulatesCache() {
        IslandId remoteId = IslandId.of(UUID.randomUUID());
        Island remoteIsland = Island.create(
                remoteId, IslandBounds.fromCenterAndRadius(300, 300, 50), ownerUuid, ownerProfileId, Instant.now());

        IslandStoragePort customStorage = new IslandStoragePort() {
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

            @Override
            public List<Island> findAllByWorld(String worldName) {
                return List.of(remoteIsland);
            }
        };

        IslandProtectionListener customListener =
                new IslandProtectionListener(customStorage, new IslandAccessService());
        assertThat(customListener.findIslandAt(new Location(world, 300, 64, 300)))
                .isEmpty();

        customListener.loadPersistedIslands(world.getName());
        assertThat(customListener.findIslandAt(new Location(world, 300, 64, 300)))
                .contains(remoteIsland);
    }

    @Test
    @DisplayName("findIslandAt queries storage and caches on cache miss")
    void findIslandAtQueriesStorageOnCacheMiss() {
        IslandId remoteId = IslandId.of(UUID.randomUUID());
        Island remoteIsland = Island.create(
                remoteId, IslandBounds.fromCenterAndRadius(400, 400, 50), ownerUuid, ownerProfileId, Instant.now());

        IslandStoragePort customStorage = new IslandStoragePort() {
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

            @Override
            public Optional<Island> findIslandByLocation(String worldName, int x, int z) {
                if (remoteIsland.bounds().contains(x, z)) {
                    return Optional.of(remoteIsland);
                }
                return Optional.empty();
            }
        };

        IslandProtectionListener customListener =
                new IslandProtectionListener(customStorage, new IslandAccessService());
        Location loc = new Location(world, 410, 64, 410);

        Optional<Island> found = customListener.findIslandAt(loc);
        assertThat(found).contains(remoteIsland);

        // Second lookup should be cached
        Optional<Island> cached = customListener.findIslandAt(loc);
        assertThat(cached).contains(remoteIsland);
    }

    @Test
    @DisplayName("pvp damage is cancelled when friendly fire shielding protects allied islands")
    void pvpCancelledWhenFriendlyFireShielded() {
        IslandId island1 = island.id();
        IslandId island2 = IslandId.of(UUID.fromString("99999999-9999-9999-9999-999999999999"));

        IslandStoragePort customStorage = new IslandStoragePort() {
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
                if (profileId.equals(ownerProfileId)) {
                    return Optional.of(island1);
                }
                if (profileId.equals(visitorProfileId)) {
                    return Optional.of(island2);
                }
                return Optional.empty();
            }

            @Override
            public void deleteIsland(IslandId id) {}
        };

        com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort allianceStorage =
                new com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort() {
                    @Override
                    public void saveAlliance(com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance alliance) {}

                    @Override
                    public void removeAlliance(IslandId islandA, IslandId islandB) {}

                    @Override
                    public boolean areAllied(IslandId islandA, IslandId islandB) {
                        return (islandA.equals(island1) && islandB.equals(island2))
                                || (islandA.equals(island2) && islandB.equals(island1));
                    }

                    @Override
                    public List<com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance> findAlliances(
                            IslandId islandId) {
                        return List.of();
                    }

                    @Override
                    public int countAlliances(IslandId islandId) {
                        return 1;
                    }

                    @Override
                    public void saveInvite(com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite invite) {}

                    @Override
                    public Optional<com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite> findInvite(
                            IslandId sender, IslandId target) {
                        return Optional.empty();
                    }

                    @Override
                    public List<com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite> findPendingInvites(
                            IslandId target, Instant now) {
                        return List.of();
                    }

                    @Override
                    public void deleteInvite(IslandId sender, IslandId target) {}

                    @Override
                    public void purgeExpiredInvites(Instant now) {}
                };

        com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService allianceService =
                new com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService(
                        allianceStorage, 2, java.time.Duration.ofMinutes(5), true, true, true);

        IslandProtectionListener customListener =
                new IslandProtectionListener(customStorage, new IslandAccessService(), allianceService);
        Island pvpIsland = island.withFlags(island.flags().withFlag(IslandFlags.PVP, true));
        customListener.cacheIsland(pvpIsland);
        customListener.setActiveProfile(ownerUuid, ownerProfileId);
        customListener.setActiveProfile(visitorUuid, visitorProfileId);

        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                visitorPlayer, ownerPlayer, EntityDamageByEntityEvent.DamageCause.ENTITY_ATTACK, 5.0);

        customListener.onEntityDamage(event);
        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("visitor with temporary access grant can break blocks on the target island")
    void visitorWithTemporaryAccessCanBreakBlocks() {
        java.util.concurrent.atomic.AtomicReference<com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant>
                storedGrant = new java.util.concurrent.atomic.AtomicReference<>();

        com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort storage =
                new com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort() {
                    @Override
                    public void save(com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant grant) {
                        storedGrant.set(grant);
                    }

                    @Override
                    public Optional<com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant> findById(
                            com.uxplima.uxmskyblock.core.domain.access.GrantId grantId) {
                        return Optional.ofNullable(storedGrant.get());
                    }

                    @Override
                    public List<com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant> findActiveByGrantee(
                            ProfileId granteeProfileId) {
                        com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant g = storedGrant.get();
                        if (g != null
                                && g.granteeProfileId().equals(granteeProfileId)
                                && g.state() == com.uxplima.uxmskyblock.core.domain.access.GrantState.ACTIVE) {
                            return List.of(g);
                        }
                        return List.of();
                    }

                    @Override
                    public List<com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant> findActiveByRoot(
                            String targetRootTypeId, String targetRootKey) {
                        com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant g = storedGrant.get();
                        if (g != null
                                && g.targetRootTypeId().equals(targetRootTypeId)
                                && g.targetRootKey().equals(targetRootKey)
                                && g.state() == com.uxplima.uxmskyblock.core.domain.access.GrantState.ACTIVE) {
                            return List.of(g);
                        }
                        return List.of();
                    }

                    @Override
                    public void updateState(
                            com.uxplima.uxmskyblock.core.domain.access.GrantId grantId,
                            com.uxplima.uxmskyblock.core.domain.access.GrantState newState,
                            Instant updatedAt) {}

                    @Override
                    public void purgeExpired(Instant now) {}
                };

        com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService tempAccessService =
                new com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService(storage);

        IslandProtectionListener customListener = new IslandProtectionListener(
                listener.islandStoragePort(), new IslandAccessService(), null, tempAccessService);

        customListener.cacheIsland(island);
        customListener.setActiveProfile(ownerUuid, ownerProfileId);
        customListener.setActiveProfile(visitorUuid, visitorProfileId);

        // Before grant: visitor is blocked
        Block block = world.getBlockAt(50, 64, 50);
        block.setType(Material.STONE);
        BlockBreakEvent eventBefore = new BlockBreakEvent(block, visitorPlayer);
        customListener.onBlockBreak(eventBefore);
        assertThat(eventBefore.isCancelled()).isTrue();

        // Issue grant with uxm:block.break
        tempAccessService.issueGrant(
                "skyblock-01",
                "ISLAND",
                island.id().value().toString(),
                visitorProfileId,
                visitorUuid,
                com.uxplima.uxmskyblock.core.domain.profile.ProfileType.CLASSIC,
                ownerProfileId,
                com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy.UNTIL_REVOKED,
                null,
                null,
                null,
                null,
                java.util.Set.of(com.uxplima.uxmskyblock.core.domain.permission.PermissionKey.of("uxm:block.break")),
                null);

        // After grant: visitor can break block
        BlockBreakEvent eventAfter = new BlockBreakEvent(block, visitorPlayer);
        customListener.onBlockBreak(eventAfter);
        assertThat(eventAfter.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("frozen island blocks modifications for non-staff players")
    void frozenIslandBlocksActionsForNormalPlayers() {
        Island frozenIsland = island.freeze("Staff quarantine investigation");
        listener.cacheIsland(frozenIsland);

        Block block = world.getBlockAt(50, 64, 50);
        block.setType(Material.STONE);

        // Block break blocked
        BlockBreakEvent breakEvent = new BlockBreakEvent(block, ownerPlayer);
        listener.onBlockBreak(breakEvent);
        assertThat(breakEvent.isCancelled()).isTrue();

        // Block place blocked
        BlockPlaceEvent placeEvent = new BlockPlaceEvent(
                block, block.getState(), block, new ItemStack(Material.STONE), ownerPlayer, true, EquipmentSlot.HAND);
        listener.onBlockPlace(placeEvent);
        assertThat(placeEvent.isCancelled()).isTrue();

        // Interact blocked
        PlayerInteractEvent interactEvent = new PlayerInteractEvent(
                ownerPlayer,
                Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.STICK),
                block,
                org.bukkit.block.BlockFace.UP);
        listener.onPlayerInteract(interactEvent);
        assertThat(interactEvent.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("frozen island allows staff inspector to modify and inspect")
    void frozenIslandAllowsStaffInspector() {
        Island frozenIsland = island.freeze("Staff quarantine investigation");
        listener.cacheIsland(frozenIsland);

        PlayerMock staffPlayer = createPlayer("StaffInspector");
        staffPlayer.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), CatalogPermissions.ADMIN_INSPECT.node(), true);
        listener.setActiveProfile(new PlayerUuid(staffPlayer.getUniqueId()), new ProfileId(staffPlayer.getUniqueId()));

        Block block = world.getBlockAt(50, 64, 50);
        block.setType(Material.STONE);

        BlockBreakEvent breakEvent = new BlockBreakEvent(block, staffPlayer);
        listener.onBlockBreak(breakEvent);
        assertThat(breakEvent.isCancelled()).isFalse();
    }
}
