package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.domain.home.Home;
import org.jspecify.annotations.Nullable;

/**
 * A player's homes as a window, one tile each: a click goes there, a shift right click deletes it.
 *
 * <p>The homes window had a list button that wrote the homes in chat, as a name and three numbers each, and a
 * player then typed the name to go there. The window is {@code menus/island-home-list.conf}, drawn from the homes
 * the command already read, and every click runs a command under the names this server gave it.
 */
public final class HomeList {

    /** The menu file that draws the homes, and the list it draws them from. */
    public static final String FILE = "island-home-list";

    static final String HOMES = "skyblock:homes";

    /** The verb a home's tile runs to go there. */
    static final String TRAVEL = "skyblock:home-travel";

    /** The verb a home's tile runs to delete it. */
    static final String DELETE = "skyblock:home-delete";

    private final UnaryOperator<String> typed;
    private volatile @Nullable SkyblockMenuEngine engine;

    /** @param typed an island command line under the operator's names, as {@code gohome base} */
    public HomeList(UnaryOperator<String> typed) {
        this.typed = Objects.requireNonNull(typed, "typed must not be null");
    }

    /** Hands this window the engine that reads its file, and teaches the engine what a click on a home does. */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.engine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(HOMES);
        engine.action(
                TRAVEL,
                ctx -> MenuRow.handle(ctx.context(), Home.class).ifPresent(home -> {
                    ctx.player().closeInventory();
                    ctx.player().performCommand(typed.apply("gohome " + home.name()));
                }));
        engine.action(
                DELETE,
                ctx -> MenuRow.handle(ctx.context(), Home.class).ifPresent(home -> {
                    ctx.player().closeInventory();
                    ctx.player().performCommand(typed.apply("delhome " + home.name()));
                }));
    }

    /** One row per home: its name, its world and where in it. */
    static List<MenuRow> rows(List<Home> homes) {
        List<MenuRow> rows = new ArrayList<>(homes.size());
        for (Home home : homes) {
            rows.add(new MenuRow(
                    Map.of(
                            "name", home.name(),
                            "world", home.worldName(),
                            "x", Long.toString(Math.round(home.x())),
                            "y", Long.toString(Math.round(home.y())),
                            "z", Long.toString(Math.round(home.z()))),
                    home));
        }
        return List.copyOf(rows);
    }

    /**
     * Shows the homes to a player, on the thread that owns them, and answers whether a window opened. It answers
     * false when the operator removed the file, so the command lists them in chat instead.
     *
     * @param allowance how many homes the player may hold
     */
    public boolean show(Player player, List<Home> homes, int allowance) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(homes, "homes must not be null");
        SkyblockMenuEngine current = this.engine;
        return current != null
                && current.open(
                        player,
                        FILE,
                        Map.of("count", Integer.toString(homes.size()), "max", Integer.toString(allowance)),
                        Map.of(HOMES, rows(homes)));
    }
}
