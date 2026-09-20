package com.uxplima.uxmskyblock.bukkit.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import org.jspecify.annotations.Nullable;

/**
 * Utility for serializing and deserializing Bukkit player inventory, equipment, ender chest,
 * potion effects, and player stats to/from {@link ProfileInventoryRecord}.
 */
@SuppressWarnings("deprecation")
public final class BukkitInventorySerializer {

    private BukkitInventorySerializer() {}

    /**
     * Serializes an array of ItemStacks to binary payload.
     */
    public static byte[] serializeItemStacks(ItemStack @Nullable [] items) {
        if (items == null || items.length == 0) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.length);
            for (ItemStack item : items) {
                boos.writeObject(item);
            }
            boos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize ItemStack array", e);
        }
    }

    /**
     * Deserializes binary payload into an array of ItemStacks.
     */
    public static ItemStack[] deserializeItemStacks(byte @Nullable [] data) {
        if (data == null || data.length == 0) {
            return new ItemStack[0];
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int length = bois.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) bois.readObject();
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize ItemStack array", e);
        }
    }

    /**
     * Serializes a collection of active potion effects to binary payload.
     */
    public static byte[] serializePotionEffects(Collection<PotionEffect> effects) {
        if (effects == null || effects.isEmpty()) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(effects.size());
            for (PotionEffect effect : effects) {
                boos.writeObject(effect);
            }
            boos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize potion effects", e);
        }
    }

    /**
     * Deserializes binary payload into a collection of potion effects.
     */
    @SuppressWarnings("unchecked")
    public static Collection<PotionEffect> deserializePotionEffects(byte @Nullable [] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int count = bois.readInt();
            List<PotionEffect> effects = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                effects.add((PotionEffect) bois.readObject());
            }
            return effects;
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to deserialize potion effects", e);
        }
    }

    /**
     * Snapshots the live player's inventory, ender chest, and stats into a {@link ProfileInventoryRecord}.
     *
     * <p>Must be invoked on the player's owning entity thread.
     */
    public static ProfileInventoryRecord snapshotPlayer(Player player, ProfileId profileId, long version) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(profileId, "profileId");

        byte[] invBytes = serializeItemStacks(player.getInventory().getContents());
        byte[] ecBytes = serializeItemStacks(player.getEnderChest().getContents());
        byte[] potionBytes = serializePotionEffects(player.getActivePotionEffects());

        Location loc = player.getLocation();
        String worldName =
                (loc != null && loc.getWorld() != null) ? loc.getWorld().getName() : null;
        double locX = loc != null ? loc.getX() : 0.0;
        double locY = loc != null ? loc.getY() : 0.0;
        double locZ = loc != null ? loc.getZ() : 0.0;

        return new ProfileInventoryRecord(
                profileId,
                version,
                invBytes,
                ecBytes,
                player.getTotalExperience(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                potionBytes,
                worldName,
                locX,
                locY,
                locZ,
                player.getGameMode().name(),
                player.getAllowFlight());
    }

    /**
     * Applies the given {@link ProfileInventoryRecord} onto the live player.
     *
     * <p>Must be invoked on the player's owning entity thread.
     */
    public static void applyToPlayer(Player player, ProfileInventoryRecord record) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(record, "record");

        // Inventory
        ItemStack[] items = deserializeItemStacks(record.inventoryNbt());
        if (items.length > 0) {
            player.getInventory().setContents(items);
        } else {
            player.getInventory().clear();
        }

        // Ender Chest
        ItemStack[] ecItems = deserializeItemStacks(record.enderchestNbt());
        if (ecItems.length > 0) {
            player.getEnderChest().setContents(ecItems);
        } else {
            player.getEnderChest().clear();
        }

        // Experience
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);
        if (record.experiencePoints() > 0) {
            player.giveExp(record.experiencePoints());
        }

        // Health
        double maxHealth = 20.0;
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealth = maxHealthAttr.getValue();
        }
        player.setHealth(Math.max(1.0, Math.min(record.health(), maxHealth)));

        // Food & Saturation
        player.setFoodLevel(record.foodLevel());
        player.setSaturation(record.saturation());

        // Potion Effects
        for (PotionEffect current : player.getActivePotionEffects()) {
            player.removePotionEffect(current.getType());
        }
        for (PotionEffect effect : deserializePotionEffects(record.activePotionEffectsNbt())) {
            player.addPotionEffect(effect);
        }

        // GameMode
        try {
            player.setGameMode(GameMode.valueOf(record.gamemode()));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Keep existing gamemode
        }

        // Flight
        player.setAllowFlight(record.flightAllowed());
    }

    /**
     * Resolves the saved logout location if available and valid in the given server.
     */
    public static Optional<Location> resolveLogoutLocation(ProfileInventoryRecord record, Server server) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(server, "server");

        if (record.logoutWorld() == null
                || record.logoutX() == null
                || record.logoutY() == null
                || record.logoutZ() == null) {
            return Optional.empty();
        }

        World world = server.getWorld(record.logoutWorld());
        if (world == null) {
            return Optional.empty();
        }

        return Optional.of(new Location(world, record.logoutX(), record.logoutY(), record.logoutZ()));
    }
}
