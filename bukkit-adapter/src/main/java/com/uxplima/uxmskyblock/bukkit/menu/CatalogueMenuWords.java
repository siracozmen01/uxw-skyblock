package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.Map;
import java.util.Objects;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
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

    /**
     * Every value is unparsed: a menu placeholder holds an island name, never markup.
     *
     * <p>A catalogue line may also spell {@code <argument_<name>>} for a value the menu was opened
     * with. The line a menu file writes is only {@code @key}, so the engine hands over no values for
     * it up front; it answers a name it is asked for through its placeholders, where
     * {@link SkyblockMenuEngine#answerArguments} puts the menu's values. Only {@code argument_} names
     * are asked, so a colour tag is never taken for a placeholder.
     */
    private static TagResolver[] resolvers(Map<String, String> placeholders) {
        TagResolver[] spelled = placeholders.entrySet().stream()
                .map(entry -> (TagResolver) Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
        TagResolver[] all = java.util.Arrays.copyOf(spelled, spelled.length + 1);
        all[spelled.length] = new ArgumentTags(placeholders);
        return all;
    }

    /** Fills {@code <argument_<name>>} from the values the menu was opened with, asked by name. */
    private record ArgumentTags(Map<String, String> placeholders) implements TagResolver {

        private static final String PREFIX = "argument_";

        @Override
        public @org.jspecify.annotations.Nullable Tag resolve(
                String name, ArgumentQueue arguments, net.kyori.adventure.text.minimessage.Context ctx) {
            String value = valueOf(name);
            return value == null ? null : Tag.selfClosingInserting(Component.text(value));
        }

        @Override
        public boolean has(String name) {
            return valueOf(name) != null;
        }

        private @org.jspecify.annotations.Nullable String valueOf(String name) {
            return name.startsWith(PREFIX) ? placeholders.get(name) : null;
        }
    }
}
