package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.Map;
import java.util.Objects;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.gui.GuiText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * Where the menu engine gets its words: this plugin's own catalog, in the language each viewer reads.
 *
 * <p>The library ships no implementation of {@link GuiText} on purpose, because an implementation
 * decides what a key means and which language a viewer gets. Both of those answers are already made
 * here, by {@link Messages}, so this is the whole of the adapter.
 */
public final class CatalogueMenuWords implements GuiText {

    private final Messages messages;

    public CatalogueMenuWords(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @Override
    public Component text(Player viewer, String key, Map<String, String> placeholders) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(placeholders, "placeholders must not be null");
        return messages.renderPlain(viewer, key, resolvers(placeholders));
    }

    @Override
    public Component render(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return messages.provider().renderTemplate(raw);
    }

    @Override
    public Component renderFor(Player viewer, String raw, Map<String, String> placeholders) {
        Objects.requireNonNull(raw, "raw must not be null");
        Objects.requireNonNull(placeholders, "placeholders must not be null");
        return messages.provider().renderTemplate(raw, resolvers(placeholders));
    }

    /**
     * A menu title is the only thing on its line, so it never carries the chat prefix. Every key here
     * is already rendered without it, which is why this answers the same as {@link #text}.
     */
    @Override
    public Component textUnprefixed(Player viewer, String key, Map<String, String> placeholders) {
        return text(viewer, key, placeholders);
    }

    /** Every value is unparsed: a menu placeholder holds an island name, never markup. */
    private static TagResolver[] resolvers(Map<String, String> placeholders) {
        return placeholders.entrySet().stream()
                .map(entry -> (TagResolver) Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
    }
}
