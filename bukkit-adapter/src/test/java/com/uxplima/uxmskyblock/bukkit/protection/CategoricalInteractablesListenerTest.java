package com.uxplima.uxmskyblock.bukkit.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.uxplima.uxmskyblock.bukkit.config.InteractablesConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"deprecation", "removal"})
class CategoricalInteractablesListenerTest extends MockBukkitHarness {

    private World world;
    private PlayerMock ownerPlayer;
    private PlayerMock visitorPlayer;
    private Island island;
    private InteractablesConfiguration config;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        ownerPlayer = createPlayer("IslandOwner");
        visitorPlayer = createPlayer("Visitor");
        config = InteractablesConfiguration.defaultConfiguration();

        island = Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(ownerPlayer.getUniqueId()),
                new ProfileId(ownerPlayer.getUniqueId()),
                Instant.now());
    }

    @Test
    @DisplayName("Allows owner to interact with doors and containers")
    void allowsOwnerInteraction() {
        CategoricalInteractablesListener listener = new CategoricalInteractablesListener(
                config, loc -> Optional.of(island), uuid -> new ProfileId(uuid.value()), null, Messages.bundled());

        Block door = world.getBlockAt(0, 64, 0);
        door.setType(Material.OAK_DOOR);

        PlayerInteractEvent event =
                new PlayerInteractEvent(ownerPlayer, Action.RIGHT_CLICK_BLOCK, null, door, null, EquipmentSlot.HAND);
        listener.onPlayerInteract(event);

        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Blocks unpermitted visitor from interacting with doors and containers")
    void blocksVisitorWithoutPermission() {
        CategoricalInteractablesListener listener = new CategoricalInteractablesListener(
                config, loc -> Optional.of(island), uuid -> new ProfileId(uuid.value()), null, Messages.bundled());

        Block door = world.getBlockAt(0, 64, 0);
        door.setType(Material.OAK_DOOR);

        PlayerInteractEvent event =
                new PlayerInteractEvent(visitorPlayer, Action.RIGHT_CLICK_BLOCK, null, door, null, EquipmentSlot.HAND);
        listener.onPlayerInteract(event);

        assertThat(event.isCancelled()).isTrue();
    }

    /** Puts a member on the island under a role holding exactly these permissions. */
    private Island islandWhereTheVisitorHolds(com.uxplima.uxmskyblock.core.domain.island.IslandPermission... held) {
        com.uxplima.uxmskyblock.core.domain.island.IslandRole role =
                new com.uxplima.uxmskyblock.core.domain.island.IslandRole(
                        "CUSTOM",
                        400,
                        "Custom",
                        held.length == 0
                                ? java.util.EnumSet.noneOf(
                                        com.uxplima.uxmskyblock.core.domain.island.IslandPermission.class)
                                : java.util.EnumSet.of(held[0], held),
                        false);
        return island.addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                new PlayerUuid(visitorPlayer.getUniqueId()),
                new ProfileId(visitorPlayer.getUniqueId()),
                role,
                Instant.now()));
    }

    private boolean refused(Island on, Material material) {
        CategoricalInteractablesListener listener = new CategoricalInteractablesListener(
                config, loc -> Optional.of(on), uuid -> new ProfileId(uuid.value()), null, Messages.bundled());
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(material);
        PlayerInteractEvent event =
                new PlayerInteractEvent(visitorPlayer, Action.RIGHT_CLICK_BLOCK, null, block, null, EquipmentSlot.HAND);
        listener.onPlayerInteract(event);
        return event.isCancelled();
    }

    @Test
    @DisplayName("A member whose role has redstone taken off cannot pull the lever")
    void aroleWithoutRedstoneCannotPullTheLever() {
        Island island = islandWhereTheVisitorHolds(
                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.BLOCK_BREAK,
                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.NATURAL_INTERACT);

        assertThat(refused(island, Material.LEVER))
                .describedAs("the category resolved a permission and nothing read it")
                .isTrue();
    }

    @Test
    @DisplayName("A member whose role holds redstone pulls the lever")
    void aroleWithRedstonePullsTheLever() {
        Island island = islandWhereTheVisitorHolds(
                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.REDSTONE_INTERACT);

        assertThat(refused(island, Material.LEVER)).isFalse();
    }

    @Test
    @DisplayName("A member whose role has container access taken off cannot open the chest")
    void aroleWithoutContainerAccessCannotOpenTheChest() {
        Island island = islandWhereTheVisitorHolds(
                com.uxplima.uxmskyblock.core.domain.island.IslandPermission.REDSTONE_INTERACT);

        assertThat(refused(island, Material.CHEST)).isTrue();
    }

    @Test
    @DisplayName("Doors and workstations are grant keys the role editor cannot express, so a member uses them")
    void acategoryTheRoleEditorCannotExpressIsLeftToMembers() {
        Island island = islandWhereTheVisitorHolds();

        assertThat(refused(island, Material.OAK_DOOR)).describedAs("a door").isFalse();
        assertThat(refused(island, Material.CRAFTING_TABLE))
                .describedAs("a workstation")
                .isFalse();
    }

    @Test
    @DisplayName("Allows staff with admin bypass to interact with any category")
    void allowsAdminBypass() {
        CategoricalInteractablesListener listener = new CategoricalInteractablesListener(
                config, loc -> Optional.of(island), uuid -> new ProfileId(uuid.value()), null, Messages.bundled());

        visitorPlayer.addAttachment(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), "uxmskyblock.admin.bypass", true);

        Block chest = world.getBlockAt(0, 64, 0);
        chest.setType(Material.CHEST);

        PlayerInteractEvent event =
                new PlayerInteractEvent(visitorPlayer, Action.RIGHT_CLICK_BLOCK, null, chest, null, EquipmentSlot.HAND);
        listener.onPlayerInteract(event);

        assertThat(event.isCancelled()).isFalse();
    }
}
