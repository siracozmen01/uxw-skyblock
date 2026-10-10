package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.bukkit.entity.Player;

import org.jspecify.annotations.Nullable;

/**
 * A leaderboard as a window: the islands on it, each under its owner's head, and where the reader's own island
 * stands. A click on an island visits it.
 *
 * <p>The top window had a button for each board, and each one wrote ten lines in chat. The window is
 * {@code menus/island-leaderboard.conf}, drawn from the board the command already read.
 */
public final class TopList {

    /** The menu file that draws a board, and the list it draws the islands from. */
    public static final String FILE = "island-leaderboard";

    static final String ISLANDS = "skyblock:leaderboard";

    /** The verb an island's tile runs to visit it. */
    static final String VISIT = "skyblock:top-visit";

    /**
     * One island on the board, in the reader's words.
     *
     * @param owner whose island it is, or null when that could not be read
     * @param ownerName the name a visit asks for, or null when the owner has none the server knows
     */
    public record Entry(
            int place,
            String name,
            String score,
            @Nullable UUID owner,
            @Nullable String ownerName) {
        public Entry {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(score, "score must not be null");
        }
    }

    private final UnaryOperator<String> typed;
    private volatile @Nullable SkyblockMenuEngine engine;

    /** @param typed an island command line under the operator's names, as {@code visit Ada} */
    public TopList(UnaryOperator<String> typed) {
        this.typed = Objects.requireNonNull(typed, "typed must not be null");
    }

    /** Hands this window the engine that reads its file, and teaches the engine what a click on an island does. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(ISLANDS);
        engine.action(
                VISIT,
                ctx -> MenuRow.handle(ctx.context(), Entry.class)
                        .map(Entry::ownerName)
                        .ifPresent(owner -> {
                            ctx.player().closeInventory();
                            ctx.player().performCommand(typed.apply("visit " + owner));
                        }));
    }

    /** One row per island: its place, its name, its score, and its owner's head. */
    static List<MenuRow> rows(List<Entry> entries) {
        List<MenuRow> rows = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Map<String, String> words = new HashMap<>();
            words.put("place", Integer.toString(entry.place()));
            words.put("name", entry.name());
            words.put("score", entry.score());
            words.put("owner", entry.ownerName() == null ? "?" : entry.ownerName());
            words.put("head", entry.owner() == null ? "PLAYER_HEAD" : "head:" + entry.owner());
            rows.add(new MenuRow(words, entry));
        }
        return List.copyOf(rows);
    }

    /**
     * Shows a board to a player, on the thread that owns them, and answers whether a window opened. It answers
     * false when the operator removed the file, so the command lists the board in chat instead.
     *
     * @param board the board's name in the reader's words
     * @param rank where the reader's own island stands, in their words
     */
    public boolean show(Player player, String board, String rank, List<Entry> entries) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        SkyblockMenuEngine current = this.engine;
        return current != null
                && current.open(
                        player,
                        FILE,
                        Map.of("board", board, "rank", rank, "count", Integer.toString(entries.size())),
                        Map.of(ISLANDS, rows(entries)));
    }
}
