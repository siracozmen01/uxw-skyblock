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
    private final SkyblockTiles tiles;

    public CatalogueMenuWords(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.tiles = new SkyblockTiles(messages);
    }

    @Override
    public Component text(Player viewer, String key, Map<String, String> placeholders) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(placeholders, "placeholders must not be null");
        return messages.renderPlain(viewer, key, resolvers(viewer, placeholders));
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
        // A tile is a whole tooltip whose words live in the catalogue, read in the viewer's language.
        if (SkyblockTiles.marks(raw)) {
            return tiles.lore(viewer, raw, resolvers(viewer, placeholders));
        }
        return messages.provider().renderTemplate(raw, resolvers(viewer, placeholders));
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
     * are asked, so a colour tag is never taken for a placeholder. A tile a list stamps once per entry asks
     * for that entry's values the same way, as {@code <entry_<name>>}.
     */
    private TagResolver[] resolvers(Player viewer, Map<String, String> placeholders) {
        TagResolver[] spelled = placeholders.entrySet().stream()
                .map(entry -> (TagResolver) Placeholder.unparsed(entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
        TagResolver[] all = java.util.Arrays.copyOf(spelled, spelled.length + 1);
        all[spelled.length] = new ArgumentTags(messages, viewer, placeholders);
        return all;
    }

    /**
     * Fills {@code <argument_<name>>} from the values the menu was opened with, asked by name.
     *
     * <p>A value is words, drawn as written. Two shapes are read instead: a lone {@code <lang:key>} is
     * drawn in the reader's client language, which is how an item keeps its own name, and a run of
     * {@code <key:path>} is those catalogue lines in the reader's language and colours, which is how a
     * state reads in its role. Neither reaches anything but the catalogue and the client's own words.
     */
    private record ArgumentTags(Messages messages, Player viewer, Map<String, String> placeholders)
            implements TagResolver {

        private static final String PREFIX = "argument_";

        /** A value that is one translation and nothing else, such as an item's name, drawn in the reader's client. */
        private static final java.util.regex.Pattern TRANSLATED =
                java.util.regex.Pattern.compile("<lang:([a-z0-9_.-]+)>");

        /** A value that is one or more catalogue lines, such as a state in its colour or a progress bar. */
        private static final java.util.regex.Pattern CATALOGUED =
                java.util.regex.Pattern.compile("(?:<key:[a-z0-9_.-]+>)+");

        private static final java.util.regex.Pattern CATALOGUE_KEY =
                java.util.regex.Pattern.compile("<key:([a-z0-9_.-]+)>");

        /** A value of the list entry a tile is drawn for, such as one member of the island. */
        private static final String ENTRY = "entry_";

        @Override
        public @org.jspecify.annotations.Nullable Tag resolve(
                String name, ArgumentQueue arguments, net.kyori.adventure.text.minimessage.Context ctx) {
            String value = valueOf(name);
            if (value == null) {
                return null;
            }
            java.util.regex.Matcher translated = TRANSLATED.matcher(value);
            if (translated.matches()) {
                return Tag.selfClosingInserting(Component.translatable(translated.group(1)));
            }
            if (CATALOGUED.matcher(value).matches()) {
                net.kyori.adventure.text.TextComponent.Builder lines = Component.text();
                java.util.regex.Matcher key = CATALOGUE_KEY.matcher(value);
                while (key.find()) {
                    // The line may ask for a value of its own, such as how long a vessel has left to go.
                    lines.append(messages.renderPlain(viewer, key.group(1), this));
                }
                return Tag.selfClosingInserting(lines.build());
            }
            return Tag.selfClosingInserting(Component.text(value));
        }

        @Override
        public boolean has(String name) {
            return valueOf(name) != null;
        }

        private @org.jspecify.annotations.Nullable String valueOf(String name) {
            return name.startsWith(PREFIX) || name.startsWith(ENTRY) ? placeholders.get(name) : null;
        }
    }
}
