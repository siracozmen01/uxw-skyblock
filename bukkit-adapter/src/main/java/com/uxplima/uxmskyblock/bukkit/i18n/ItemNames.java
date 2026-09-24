package com.uxplima.uxmskyblock.bukkit.i18n;

import java.util.Objects;

import org.bukkit.Material;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * An item as the reader's own game names it.
 *
 * <p>The shop and the missions named items by the key the server keeps, so a Turkish player bought
 * GOLD_INGOT and was asked for SUGAR_CANE. A material the game knows is sent as its translation, which
 * every client shows in its own language without a line in the catalogue; a key that names no
 * material, such as one a player typed, is shown as it was written.
 */
public final class ItemNames {

    private ItemNames() {}

    /** The item {@code itemKey} names, as a component the reader's client translates. */
    public static Component of(String itemKey) {
        Objects.requireNonNull(itemKey, "itemKey");
        Material material = Material.matchMaterial(itemKey);
        return material == null ? Component.text(itemKey) : Component.translatable(material.translationKey());
    }

    /** A {@code <tag>} filled with the item {@code itemKey} names. */
    public static TagResolver placeholder(String tag, String itemKey) {
        return Placeholder.component(tag, of(itemKey));
    }
}
