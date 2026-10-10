package com.uxplima.uxmskyblock.bukkit.limit;

import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;

/**
 * A limited block or entity as the reader's language names it.
 *
 * <p>{@code /is limits} and the refusal at the cap named every kind by its constant, so a Turkish
 * player read {@code STICKY_PISTON 0/32} and {@code ARMOR_STAND 0/30}. A line the catalogue holds
 * under {@code limits.types} names it first, so an operator can rename one. A block or an entity the
 * game knows is sent as its translation, which every client shows in its own language. Only a kind
 * neither names keeps its constant.
 */
public final class LimitNames {

    private LimitNames() {}

    public static Component of(Messages messages, Audience reader, LimitType type) {
        String id = type.name().toLowerCase(Locale.ROOT);
        String key = "limits.types." + id;
        if (messages.has(key)) {
            return messages.renderPlain(reader, key);
        }
        Material material = Material.getMaterial(type.name());
        if (material != null) {
            return Component.translatable(material.translationKey());
        }
        EntityType entity = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(id));
        if (entity != null) {
            return Component.translatable(entity.translationKey());
        }
        return Component.text(type.name());
    }
}
