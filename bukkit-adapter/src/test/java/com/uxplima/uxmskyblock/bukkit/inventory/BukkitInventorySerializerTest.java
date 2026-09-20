package com.uxplima.uxmskyblock.bukkit.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class BukkitInventorySerializerTest extends MockBukkitHarness {

    @Test
    @DisplayName("serializes and deserializes ItemStacks round-trip")
    void itemStacksRoundTrip() {
        ItemStack[] items = new ItemStack[4];
        items[0] = new ItemStack(Material.DIAMOND, 10);
        items[1] = null; // empty slot
        items[2] = new ItemStack(Material.IRON_SWORD);
        items[3] = new ItemStack(Material.OAK_LOG, 64);

        byte[] serialized = BukkitInventorySerializer.serializeItemStacks(items);
        assertThat(serialized).isNotEmpty();

        ItemStack[] deserialized = BukkitInventorySerializer.deserializeItemStacks(serialized);
        assertThat(deserialized).hasSize(4);
        assertThat(deserialized[0]).isEqualTo(items[0]);
        assertThat(deserialized[1]).isNull();
        assertThat(deserialized[2]).isEqualTo(items[2]);
        assertThat(deserialized[3]).isEqualTo(items[3]);
    }

    @Test
    @DisplayName("handles empty and null item stack arrays")
    void handlesEmptyItemStacks() {
        assertThat(BukkitInventorySerializer.serializeItemStacks(null)).isEmpty();
        assertThat(BukkitInventorySerializer.serializeItemStacks(new ItemStack[0]))
                .isEmpty();
        assertThat(BukkitInventorySerializer.deserializeItemStacks(null)).isEmpty();
        assertThat(BukkitInventorySerializer.deserializeItemStacks(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("serializes and deserializes potion effects round-trip")
    void potionEffectsRoundTrip() {
        PotionEffect effect1 = new PotionEffect(PotionEffectType.SPEED, 100, 1);
        PotionEffect effect2 = new PotionEffect(PotionEffectType.REGENERATION, 200, 0);
        List<PotionEffect> effects = List.of(effect1, effect2);

        byte[] serialized = BukkitInventorySerializer.serializePotionEffects(effects);
        assertThat(serialized).isNotEmpty();

        var deserialized = BukkitInventorySerializer.deserializePotionEffects(serialized);
        assertThat(deserialized).hasSize(2);
        assertThat(deserialized).contains(effect1, effect2);
    }

    @Test
    @DisplayName("snapshotPlayer captures live player state into ProfileInventoryRecord")
    void snapshotPlayerCapturesState() {
        PlayerMock player = createPlayer("TestPlayer");
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_INGOT, 5));
        player.getEnderChest().setItem(0, new ItemStack(Material.EMERALD, 3));
        player.setHealth(15.0);
        player.setFoodLevel(18);
        player.setSaturation(4.5f);
        player.setTotalExperience(120);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(true);

        ProfileId profileId = new ProfileId(UUID.randomUUID());
        ProfileInventoryRecord record = BukkitInventorySerializer.snapshotPlayer(player, profileId, 5L);

        assertThat(record.profileId()).isEqualTo(profileId);
        assertThat(record.version()).isEqualTo(5L);
        assertThat(record.health()).isEqualTo(15.0);
        assertThat(record.foodLevel()).isEqualTo(18);
        assertThat(record.saturation()).isEqualTo(4.5f);
        assertThat(record.gamemode()).isEqualTo("ADVENTURE");
        assertThat(record.flightAllowed()).isTrue();

        ItemStack[] deserializedInv = BukkitInventorySerializer.deserializeItemStacks(record.inventoryNbt());
        assertThat(deserializedInv[0]).isEqualTo(new ItemStack(Material.GOLD_INGOT, 5));

        ItemStack[] deserializedEc = BukkitInventorySerializer.deserializeItemStacks(record.enderchestNbt());
        assertThat(deserializedEc[0]).isEqualTo(new ItemStack(Material.EMERALD, 3));
    }

    @Test
    @DisplayName("applyToPlayer restores inventory and stats on the player")
    void applyToPlayerRestoresState() {
        PlayerMock player = createPlayer("RestorePlayer");
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        ItemStack[] inv = new ItemStack[41];
        inv[0] = new ItemStack(Material.NETHERITE_PICKAXE);
        byte[] invBytes = BukkitInventorySerializer.serializeItemStacks(inv);

        ItemStack[] ec = new ItemStack[27];
        ec[0] = new ItemStack(Material.OBSIDIAN, 64);
        byte[] ecBytes = BukkitInventorySerializer.serializeItemStacks(ec);

        ProfileInventoryRecord record = new ProfileInventoryRecord(
                profileId,
                1L,
                invBytes,
                ecBytes,
                500,
                12.0,
                14,
                2.5f,
                new byte[0],
                "world",
                0.0,
                64.0,
                0.0,
                "SURVIVAL",
                false);

        BukkitInventorySerializer.applyToPlayer(player, record);

        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.NETHERITE_PICKAXE));
        assertThat(player.getEnderChest().getItem(0)).isEqualTo(new ItemStack(Material.OBSIDIAN, 64));
        assertThat(player.getHealth()).isEqualTo(12.0);
        assertThat(player.getFoodLevel()).isEqualTo(14);
        assertThat(player.getSaturation()).isEqualTo(2.5f);
        assertThat(player.getTotalExperience()).isEqualTo(500);
        assertThat(player.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(player.getAllowFlight()).isFalse();
    }

    @Test
    @DisplayName("resolveLogoutLocation resolves valid and invalid world names")
    void resolvesLogoutLocation() {
        World world = server.addSimpleWorld("skyblock_world");
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        ProfileInventoryRecord validRecord = new ProfileInventoryRecord(
                profileId,
                1L,
                new byte[0],
                new byte[0],
                0,
                20.0,
                20,
                5.0f,
                new byte[0],
                "skyblock_world",
                10.0,
                70.0,
                -15.0,
                "SURVIVAL",
                false);

        Optional<Location> validLoc = BukkitInventorySerializer.resolveLogoutLocation(validRecord, server);
        assertThat(validLoc).isPresent();
        assertThat(validLoc.get().getWorld()).isEqualTo(world);
        assertThat(validLoc.get().getX()).isEqualTo(10.0);
        assertThat(validLoc.get().getY()).isEqualTo(70.0);
        assertThat(validLoc.get().getZ()).isEqualTo(-15.0);

        ProfileInventoryRecord invalidRecord = new ProfileInventoryRecord(
                profileId,
                1L,
                new byte[0],
                new byte[0],
                0,
                20.0,
                20,
                5.0f,
                new byte[0],
                "nonexistent_world",
                0.0,
                0.0,
                0.0,
                "SURVIVAL",
                false);

        Optional<Location> invalidLoc = BukkitInventorySerializer.resolveLogoutLocation(invalidRecord, server);
        assertThat(invalidLoc).isEmpty();
    }
}
