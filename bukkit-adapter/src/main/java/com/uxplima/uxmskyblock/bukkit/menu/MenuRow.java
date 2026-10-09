package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.menu.runtime.MenuContext;

/**
 * One entry of a list a window gathered in code and a menu file draws: the words its tile asks for, and
 * the thing a click on it acts on.
 *
 * <p>A tile asks for a word as {@code <entry_<name>>} and a file names it as {@code %entry_<name>%}, so
 * {@code words} holds {@code name} without the prefix. A word that is a {@code <lang:key>} and nothing
 * else is drawn as that translation in the reader's client, which is how an item keeps its own name.
 */
public record MenuRow(Map<String, String> words, Object handle) {

    public MenuRow {
        words = Map.copyOf(Objects.requireNonNull(words, "words must not be null"));
        Objects.requireNonNull(handle, "handle must not be null");
    }

    /** The row a list stamped this tile for, when it was one of these. */
    public static Optional<MenuRow> of(MenuContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        return ctx.entry().filter(MenuRow.class::isInstance).map(MenuRow.class::cast);
    }

    /** The thing a click on this row acts on, when it is of {@code type}. */
    public static <T> Optional<T> handle(MenuContext ctx, Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return of(ctx).map(MenuRow::handle).filter(type::isInstance).map(type::cast);
    }
}
