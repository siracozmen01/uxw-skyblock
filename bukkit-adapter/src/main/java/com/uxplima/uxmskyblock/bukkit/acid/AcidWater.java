package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import io.papermc.paper.potion.PotionMix;

import org.jspecify.annotations.Nullable;

/**
 * Water on an AcidIsland island is acid until it is made clean.
 *
 * <p>A clean bottle carries a mark and a glint, so a player tells it apart from a sea bottle at a
 * glance. Every water bottle without the mark is acid there: one filled from the sea, one brought from
 * elsewhere and one made in any way the plugin did not see. A bottle is made clean in a furnace, in a
 * brewing stand with coal, or by filling it from a cauldron, which holds the rain.
 *
 * <p>A cauldron a bucket of sea water was poured into holds the sea, not the rain, and a bottle from it
 * is acid until the cauldron is emptied. A bucket smelted in a furnace comes out clean and marked, and
 * poured into a cauldron keeps it clean. The mark on a cauldron is kept on its chunk.
 */
public final class AcidWater {

    public static final NamespacedKey CLEAN =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:clean_water"));
    public static final NamespacedKey FURNACE =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:purify_water"));
    public static final NamespacedKey BREWING =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:purify_water_with_coal"));

    public static final NamespacedKey FURNACE_BUCKET =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:purify_water_bucket"));
    public static final NamespacedKey ACID_CAULDRONS =
            Objects.requireNonNull(NamespacedKey.fromString("uxmskyblock:acid_cauldrons"));

    private static final float FURNACE_EXPERIENCE = 0.1f;
    private static final int FURNACE_TICKS = 200;

    private AcidWater() {
        throw new UnsupportedOperationException("AcidWater is a set of rules about bottles, not a thing to hold");
    }

    /** A plain bottle of water, the one a player fills. */
    public static ItemStack waterBottle() {
        ItemStack bottle = new ItemStack(Material.POTION);
        bottle.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(PotionType.WATER));
        return bottle;
    }

    /** A bottle of water made clean. */
    public static ItemStack cleanBottle() {
        ItemStack bottle = waterBottle();
        bottle.editMeta(PotionMeta.class, meta -> {
            meta.getPersistentDataContainer().set(CLEAN, PersistentDataType.BOOLEAN, true);
            meta.setEnchantmentGlintOverride(true);
        });
        return bottle;
    }

    /** A bucket of water made clean. */
    public static ItemStack cleanBucket() {
        ItemStack bucket = new ItemStack(Material.WATER_BUCKET);
        bucket.editMeta(meta -> {
            meta.getPersistentDataContainer().set(CLEAN, PersistentDataType.BOOLEAN, true);
            meta.setEnchantmentGlintOverride(true);
        });
        return bucket;
    }

    /** Whether the item is a bucket of water that was made clean. */
    public static boolean isCleanBucket(@Nullable ItemStack item) {
        return item != null
                && item.getType() == Material.WATER_BUCKET
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(CLEAN, PersistentDataType.BOOLEAN);
    }

    /** Whether the cauldron holds sea water a bucket poured in. */
    public static boolean isAcidCauldron(Block cauldron) {
        long[] marked =
                cauldron.getChunk().getPersistentDataContainer().get(ACID_CAULDRONS, PersistentDataType.LONG_ARRAY);
        if (marked == null) {
            return false;
        }
        long key = keyInChunk(cauldron);
        for (long held : marked) {
            if (held == key) {
                return true;
            }
        }
        return false;
    }

    /** Marks the cauldron as holding sea water, or clears the mark. */
    public static void markCauldron(Block cauldron, boolean acid) {
        PersistentDataContainer chunk = cauldron.getChunk().getPersistentDataContainer();
        long[] marked = chunk.getOrDefault(ACID_CAULDRONS, PersistentDataType.LONG_ARRAY, new long[0]);
        long key = keyInChunk(cauldron);
        long[] kept =
                java.util.Arrays.stream(marked).filter(held -> held != key).toArray();
        long[] next = acid
                ? java.util.stream.LongStream.concat(java.util.Arrays.stream(kept), java.util.stream.LongStream.of(key))
                        .toArray()
                : kept;
        if (next.length == 0) {
            chunk.remove(ACID_CAULDRONS);
        } else {
            chunk.set(ACID_CAULDRONS, PersistentDataType.LONG_ARRAY, next);
        }
    }

    /** The cauldron's place within its chunk, which is where the mark is kept. */
    private static long keyInChunk(Block block) {
        return ((long) block.getY() << 8) | ((block.getX() & 15L) << 4) | (block.getZ() & 15L);
    }

    /** Whether the item is a bottle of water, clean or not. */
    public static boolean isWater(@Nullable ItemStack item) {
        return item != null
                && item.getType() == Material.POTION
                && item.getItemMeta() instanceof PotionMeta meta
                && meta.getBasePotionType() == PotionType.WATER
                && !meta.hasCustomEffects();
    }

    /** Whether the item is a bottle of water that was made clean. */
    public static boolean isClean(@Nullable ItemStack item) {
        if (item == null || !isWater(item)) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(CLEAN, PersistentDataType.BOOLEAN);
    }

    /** Whether the item is a bottle of water that is acid on an AcidIsland island. */
    public static boolean isAcid(@Nullable ItemStack item) {
        return isWater(item) && !isClean(item);
    }

    /** Smelting a bottle of water makes it clean. */
    public static FurnaceRecipe furnaceRecipe() {
        return new FurnaceRecipe(
                FURNACE, cleanBottle(), new RecipeChoice.ExactChoice(waterBottle()), FURNACE_EXPERIENCE, FURNACE_TICKS);
    }

    /** Smelting a bucket of water makes it clean. */
    public static FurnaceRecipe furnaceBucketRecipe() {
        return new FurnaceRecipe(
                FURNACE_BUCKET,
                cleanBucket(),
                new RecipeChoice.ExactChoice(new ItemStack(Material.WATER_BUCKET)),
                FURNACE_EXPERIENCE,
                FURNACE_TICKS);
    }

    /** Brewing a bottle of water with coal or charcoal makes it clean. */
    public static PotionMix brewingMix() {
        return new PotionMix(
                BREWING,
                cleanBottle(),
                PotionMix.createPredicateChoice(AcidWater::isAcid),
                new RecipeChoice.MaterialChoice(Material.COAL, Material.CHARCOAL));
    }
}
