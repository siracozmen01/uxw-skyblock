package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmlib.gui.style.Lore;
import com.uxplima.uxmlib.gui.style.MenuTiles;
import com.uxplima.uxmlib.gui.style.Tiles;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmlib.text.style.Theme;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * A menu tile drawn from this plugin's catalogue: the title, the category under it, the description, the
 * facts and the line that says what a click does.
 *
 * <p>A menu file writes a tile as one lore line, {@code tile:<colour> @<key> [fact ...]}, the shape every
 * plugin of ours writes. The library reads that line against its own catalogue, and this plugin keeps its
 * words in a catalogue of its own, so the line is read here against that one. The look is still the
 * library's: {@link Lore} draws the blocks and {@link Tiles} paints the title, both from the theme.
 *
 * <p>The words of a tile live under its key: {@code <key>.title}, {@code <key>.crumb},
 * {@code <key>.description}, {@code <key>.<fact>.label} and {@code <key>.<fact>.value}, and
 * {@code <key>.action}. A block the catalogue leaves out is left off the tile.
 */
public final class SkyblockTiles {

    /** The word over the description block, and the word over the facts. Both are the same in every window. */
    static final String DESCRIPTION = "menu.lore.description";

    static final String DETAILS = "menu.lore.details";

    private static final String TITLE = ".title";
    private static final String CRUMB = ".crumb";
    private static final String TEXT = ".description";
    private static final String ACTION = ".action";

    private final Messages messages;

    public SkyblockTiles(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Whether {@code written} is a tile rather than one line of words. */
    public static boolean marks(String written) {
        return MenuTiles.marks(written);
    }

    /** The whole tooltip of one tile, as one component with the line breaks in it. */
    public Component lore(Player viewer, String written, TagResolver... resolvers) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(written, "written must not be null");
        Spec spec = Spec.read(written);
        Theme theme = messages.styler().theme();
        Lore lore = Lore.of(theme);
        if (spec.draws(CRUMB) && messages.has(spec.key + CRUMB)) {
            lore.crumb(words(viewer, spec.key + CRUMB, resolvers));
        }
        if (spec.draws(TEXT) && messages.has(spec.key + TEXT)) {
            lore.description(words(viewer, DESCRIPTION, resolvers), words(viewer, spec.key + TEXT, resolvers));
        }
        if (!spec.facts.isEmpty()) {
            lore.details(words(viewer, DETAILS, resolvers));
            for (Fact fact : spec.facts) {
                Component label = words(viewer, spec.key + "." + fact.name() + ".label", resolvers);
                Component value = words(viewer, spec.key + "." + fact.name() + ".value", resolvers);
                if (fact.state()) {
                    lore.status(label, painted(theme, value, fact.role()));
                } else {
                    lore.row(label, value);
                }
            }
        }
        String action = spec.action();
        if (spec.draws(ACTION) && messages.has(action)) {
            lore.action(words(viewer, action, resolvers));
        }
        return Tiles.titled(theme, words(viewer, spec.key + TITLE, resolvers), lore.build(), spec.colour);
    }

    /**
     * A tile as an item a window shows: a blank name, the whole tooltip in the lore, and none of the lines the
     * client would add under it, because a tile is a button and not the item it is drawn as.
     */
    public ItemStack item(Material material, Player viewer, String written, TagResolver... resolvers) {
        Objects.requireNonNull(material, "material must not be null");
        return ItemBuilder.of(material)
                .name(Tiles.blankName())
                .lore(List.of(lore(viewer, written, resolvers)))
                .vanillaTooltip(false)
                .build();
    }

    /** A button that only moves the player about: one line and no lore, such as the way back. */
    public ItemStack button(Material material, Player viewer, String key, TagResolver... resolvers) {
        Objects.requireNonNull(material, "material must not be null");
        return ItemBuilder.of(material)
                .name(words(viewer, key, resolvers))
                .vanillaTooltip(false)
                .build();
    }

    /** The pane behind every free slot: a blank name, no lore, and nothing to say. */
    public static ItemStack filler() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Tiles.blankName())
                .vanillaTooltip(false)
                .build();
    }

    /** The values a window was opened with, each one answering {@code <argument_<name>>} as plain text. */
    public static TagResolver[] arguments(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> (TagResolver) Placeholder.unparsed("argument_" + entry.getKey(), entry.getValue()))
                .toArray(TagResolver[]::new);
    }

    private Component words(Player viewer, String path, TagResolver... resolvers) {
        return messages.renderPlain(viewer, path, resolvers);
    }

    /** A state's value in the role the line named, when the catalogue gave it no colour of its own. */
    private static Component painted(Theme theme, Component value, String role) {
        if (role.isEmpty() || !theme.hasColour(role)) {
            return value;
        }
        return value.colorIfAbsent(theme.colour(role));
    }

    /** One word of the facts: which row it draws, whether it is a state, and the role a state takes. */
    private record Fact(String name, boolean state, String role) {

        static Fact of(String name) {
            return new Fact(name, false, "");
        }

        static Fact state(String written) {
            int at = written.indexOf(':');
            return at < 0
                    ? new Fact(written, true, "")
                    : new Fact(written.substring(0, at), true, written.substring(at + 1));
        }
    }

    /** What one tile line names: the colour, the block of words, the facts, the closing line, the blocks left out. */
    private record Spec(String colour, String key, List<Fact> facts, Set<String> without, String named) {

        private static final Pattern WORDS = Pattern.compile("\\s+");

        static Spec read(String written) {
            String[] words = WORDS.split(written.trim(), -1);
            String colour = words[0].substring(MenuTiles.MARK.length());
            String key = words.length > 1 ? name(words[1]) : "";
            List<Fact> facts = new ArrayList<>();
            Set<String> without = new LinkedHashSet<>();
            String named = "";
            for (int at = 2; at < words.length; at++) {
                String word = words[at];
                if (word.startsWith("-")) {
                    without.add("." + word.substring(1));
                } else if (word.startsWith(MenuTiles.ACTION_MARK)) {
                    named = name(word.substring(MenuTiles.ACTION_MARK.length()));
                } else if (word.startsWith(MenuTiles.STATE_MARK)) {
                    String marked = word.substring(MenuTiles.STATE_MARK.length());
                    if (!marked.isEmpty() && marked.charAt(0) != ':') {
                        facts.add(Fact.state(marked));
                    }
                } else if (!word.isEmpty()) {
                    facts.add(Fact.of(word));
                }
            }
            return new Spec(colour, key, List.copyOf(facts), Set.copyOf(without), named);
        }

        String action() {
            return named.isEmpty() ? key + ACTION : named;
        }

        boolean draws(String part) {
            return !without.contains(part);
        }

        private static String name(String written) {
            return written.startsWith("@") ? written.substring(1) : written;
        }
    }
}
