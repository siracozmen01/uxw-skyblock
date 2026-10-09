package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;

/**
 * The windows a feature gathers in code, joined to the menu files that draw them.
 *
 * <p>Each window reads what it shows off the player's thread and hands it to its file. The window it
 * used to build in code stays as the answer to a file that is missing or will not parse, and that one
 * goes back to the menu it came from the way a file's back button does.
 */
final class WindowFiles {

    private WindowFiles() {}

    static void connect(GameplayWiring gameplay, SkyblockMenuEngine engine, IslandControlMenu island) {
        if (gameplay.shopMenu() != null) {
            gameplay.shopMenu().useWayBack(island::open);
            gameplay.shopMenu().useMenuEngine(engine);
        }
        if (gameplay.warpBrowseMenu() != null) {
            gameplay.warpBrowseMenu().useMenuEngine(engine);
        }
        if (gameplay.resetConfirmationMenu() != null) {
            gameplay.resetConfirmationMenu().useMenuEngine(engine);
        }
        if (gameplay.boosterMenu() != null) {
            gameplay.boosterMenu().useWayBack(wayBackTo(engine, island, "island-boosters"));
            gameplay.boosterMenu().useMenuEngine(engine);
        }
        if (gameplay.missionsMenu() != null) {
            gameplay.missionsMenu().useWayBack(wayBackTo(engine, island, "island-missions"));
            gameplay.missionsMenu().useMenuEngine(engine);
        }
    }

    /** Opens the menu file a code-built window came from, or the island menu when that file is gone. */
    private static Consumer<Player> wayBackTo(SkyblockMenuEngine engine, IslandControlMenu island, String menu) {
        return viewer -> {
            if (!engine.open(viewer, menu, Map.of())) {
                island.open(viewer);
            }
        };
    }
}
