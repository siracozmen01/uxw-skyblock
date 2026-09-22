package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A role that says a member may not open a chest is a role that stops them.
 *
 * <p>The role editor has published six container permissions since the permission work and nothing
 * ever read one. An operator could take chest access off a role, the editor would show it taken
 * off, and the member kept opening every chest on the island. The interact rule is a different
 * question: it asks whether a player may touch anything here at all, and a member who may build is
 * past it long before they reach a chest.
 */
class TheContainerPermissionIsEnforcedTest extends MockBukkitHarness {

    private World world;
    private IslandProtectionListener listener;
    private Island island;
    private PlayerMock member;
    private ProfileId memberProfile;

    private static final IslandStoragePort NO_STORAGE = new IslandStoragePort() {
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

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("container_world");
        listener = new IslandProtectionListener(NO_STORAGE, new IslandAccessService());

        PlayerMock owner = createPlayer("Owner");
        member = createPlayer("Member");
        memberProfile = new ProfileId(member.getUniqueId());
        listener.setActiveProfile(new PlayerUuid(member.getUniqueId()), memberProfile);

        island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(50, 50, 50),
                new PlayerUuid(owner.getUniqueId()),
                new ProfileId(owner.getUniqueId()),
                Instant.now());
    }

    /** Puts the member on the island under a role holding exactly these permissions. */
    private void memberHolding(IslandPermission... permissions) {
        IslandRole role = new IslandRole(
                "CUSTOM",
                400,
                "Custom",
                permissions.length == 0
                        ? EnumSet.noneOf(IslandPermission.class)
                        : EnumSet.of(permissions[0], permissions),
                false);
        listener.cacheIsland(island.addMember(
                new IslandMember(new PlayerUuid(member.getUniqueId()), memberProfile, role, Instant.now())));
    }

    /** Puts a real container in the world at the island's centre and opens it for the member. */
    private InventoryOpenEvent openingA(Material container) {
        org.bukkit.block.Block block = world.getBlockAt(50, 64, 50);
        block.setType(container);
        Inventory inventory = ((org.bukkit.block.Container) block.getState()).getInventory();
        return new InventoryOpenEvent(member.openInventory(inventory));
    }

    @Test
    @DisplayName("A role without chest access cannot open a chest, however much else it may do")
    void aRoleWithoutChestAccessIsStopped() {
        memberHolding(IslandPermission.BLOCK_BREAK, IslandPermission.BLOCK_PLACE, IslandPermission.NATURAL_INTERACT);

        InventoryOpenEvent event = openingA(Material.CHEST);
        listener.onContainerOpen(event);

        assertThat(event.isCancelled())
                .describedAs("the role the operator wrote says no chest")
                .isTrue();
        assertThat(member.nextMessage())
                .describedAs("and the player is told why")
                .isNotNull();
    }

    @Test
    @DisplayName("A role with chest access opens the chest")
    void aRoleWithChestAccessIsLetThrough() {
        memberHolding(IslandPermission.CHEST_OPEN);

        InventoryOpenEvent event = openingA(Material.CHEST);
        listener.onContainerOpen(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Chest access is not furnace access: each container asks for its own permission")
    void eachContainerAsksForItsOwnPermission() {
        memberHolding(IslandPermission.CHEST_OPEN);

        InventoryOpenEvent furnace = openingA(Material.FURNACE);
        listener.onContainerOpen(furnace);

        assertThat(furnace.isCancelled())
                .describedAs("the role holds the chest permission and not the furnace one")
                .isTrue();
    }

    @Test
    @DisplayName("A container the permission list does not name is a chest as far as this rule goes")
    void anUnnamedContainerFallsUnderChestAccess() {
        memberHolding(IslandPermission.NATURAL_INTERACT);

        InventoryOpenEvent hopper = openingA(Material.HOPPER);
        listener.onContainerOpen(hopper);

        assertThat(hopper.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("The owner opens everything on their own island")
    void theOwnerIsNeverStopped() {
        listener.cacheIsland(island);
        listener.setActiveProfile(new PlayerUuid(member.getUniqueId()), island.ownerProfileId());

        InventoryOpenEvent event = openingA(Material.CHEST);
        listener.onContainerOpen(event);

        assertThat(event.isCancelled())
                .describedAs("the owner holds everything, whatever the roles say")
                .isFalse();
    }

    @Test
    @DisplayName("A window with no place in the world is nobody's container")
    void aWindowWithNoLocationIsNotChecked() {
        memberHolding();

        InventoryOpenEvent event = new InventoryOpenEvent(
                member.openInventory(server.createInventory(null, org.bukkit.event.inventory.InventoryType.CHEST)));
        listener.onContainerOpen(event);

        assertThat(event.isCancelled())
                .describedAs("the player's own window, or one this plugin drew")
                .isFalse();
    }
}
