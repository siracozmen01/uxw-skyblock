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
                config, loc -> Optional.of(island), uuid -> new ProfileId(uuid.value()), null);

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
                config, loc -> Optional.of(island), uuid -> new ProfileId(uuid.value()), null);

        Block door = world.getBlockAt(0, 64, 0);
        door.setType(Material.OAK_DOOR);

        PlayerInteractEvent event =
                new PlayerInteractEvent(visitorPlayer, Action.RIGHT_CLICK_BLOCK, null, door, null, EquipmentSlot.HAND);
        listener.onPlayerInteract(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Allows staff with admin bypass to interact with any category")
    void allowsAdminBypass() {
        CategoricalInteractablesListener listener = new CategoricalInteractablesListener(
                config, loc -> Optional.of(island), uuid -> new ProfileId(uuid.value()), null);

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
