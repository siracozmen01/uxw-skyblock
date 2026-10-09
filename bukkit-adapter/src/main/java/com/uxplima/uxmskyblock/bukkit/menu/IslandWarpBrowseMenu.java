package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import org.jspecify.annotations.Nullable;

/**
 * The public warp directory as a window, each warp wearing the icon its owner gave it.
 *
 * <p>Every warp has carried an icon material since the warp work and nothing ever drew one: the
 * directory was a list of chat lines, so the field was written, stored and never seen. A directory
 * of shops and farms is the one place in this plugin where a picture does more work than a word.
 *
 * <p>Everything the window draws is read before it reaches the thread that owns the player, because
 * that is where a window is built and opened. A click hands the visit back off that thread.
 *
 * <p>A Bedrock player gets a native form with the same warps in the same order. A chest a Bedrock
 * player cannot use properly is an unfinished window.
 */
public final class IslandWarpBrowseMenu {

    /** One warp, and everything the window needs to draw it, read before the window is built. */
    public record Entry(IslandWarp warp, String ownerName) {
        public Entry {
            Objects.requireNonNull(warp, "warp must not be null");
            Objects.requireNonNull(ownerName, "ownerName must not be null");
        }
    }

    /** The menu file that draws the directory, and the list it draws the warps from. */
    static final String FILE = "island-warp-directory";

    static final String WARPS = "skyblock:public-warps";

    private final Messages messages;
    private @Nullable BedrockFormService bedrockFormService;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /** What a choice does for each viewer with the directory open, which is the visit their command asked for. */
    private final Map<java.util.UUID, java.util.function.Consumer<Entry>> visits =
            new java.util.concurrent.ConcurrentHashMap<>();

    public IslandWarpBrowseMenu(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public void setBedrockFormService(@Nullable BedrockFormService bedrockFormService) {
        this.bedrockFormService = bedrockFormService;
    }

    /**
     * Hands this window the engine that reads {@code menus/island-warp-directory.conf}, and teaches the
     * engine what choosing a warp does. The window built here stays as the answer to a file that is
     * missing or will not parse.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine == null) {
            return;
        }
        engine.handedList(WARPS);
        engine.action(
                "skyblock:warp-visit",
                ctx -> MenuRow.handle(ctx.context(), Entry.class).ifPresent(entry -> {
                    java.util.function.Consumer<Entry> visit =
                            visits.get(ctx.player().getUniqueId());
                    if (visit != null) {
                        visit.accept(entry);
                    }
                }));
    }

    /** One row per warp: its icon, its name, its owner and its kind in the reader's language. */
    List<MenuRow> rows(Player player, List<Entry> entries) {
        List<MenuRow> rows = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            String category = entry.warp().category().name();
            rows.add(new MenuRow(
                    Map.of(
                            "material", iconOf(entry).name(),
                            "name", entry.warp().name().value(),
                            "owner", entry.ownerName(),
                            "category", messages.named(player, "warp.categories", category, category)),
                    entry));
        }
        return List.copyOf(rows);
    }

    /**
     * Shows the directory to a player, on the thread that owns them.
     *
     * <p>What to show has already been read: this does no storage work at all.
     *
     * @param onVisit what to do when a warp is chosen, which is the command's own visit flow
     */
    public void show(Player player, List<Entry> entries, java.util.function.Consumer<Entry> onVisit) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(onVisit, "onVisit must not be null");

        BedrockFormService forms = this.bedrockFormService;
        if (forms != null && forms.isBedrock(player)) {
            openForm(forms, player, entries, onVisit);
            return;
        }
        SkyblockMenuEngine engine = this.menuEngine;
        if (engine != null) {
            visits.put(player.getUniqueId(), onVisit);
            if (engine.open(player, FILE, Map.of(), Map.of(WARPS, rows(player, entries)))) {
                return;
            }
        }
        buildGui(player, entries, onVisit).open(player);
    }

    /** Builds the window from what was already read, so nothing here reaches the database. */
    public SimpleGui buildGui(Player player, List<Entry> entries, java.util.function.Consumer<Entry> onVisit) {
        int rows = Math.min(6, Math.max(2, (entries.size() / 9) + 2));
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "menu.warp_browse.title")))
                .rows(rows)
                .build();
        gui.filler().fillRow(rows, GuiItem.display(SkyblockTiles.filler()));

        SkyblockTiles tiles = new SkyblockTiles(messages);
        int slot = 0;
        int lastRowStart = (rows - 1) * 9;
        for (Entry entry : entries) {
            if (slot >= lastRowStart) {
                break;
            }
            gui.set(slot++, GuiItem.button(tile(tiles, player, entry), event -> {
                event.setCancelled(true);
                onVisit.accept(entry);
            }));
        }
        return gui;
    }

    /** One warp's tile, in the window built here. */
    private ItemStack tile(SkyblockTiles tiles, Player player, Entry entry) {
        String category = entry.warp().category().name();
        return tiles.item(
                iconOf(entry),
                player,
                "tile:3 @menu.warp_browse.tile owner kind",
                Placeholder.unparsed("entry_name", entry.warp().name().value()),
                Placeholder.unparsed("entry_owner", entry.ownerName()),
                Placeholder.unparsed("entry_category", messages.named(player, "warp.categories", category, category)));
    }

    /**
     * The icon the warp says it is. A warp naming a material this server does not have still gets a
     * tile: losing a shop from the directory because its owner picked a block a later version renamed
     * is worse than showing it under a compass.
     */
    private static Material iconOf(Entry entry) {
        Material material = Material.matchMaterial(entry.warp().iconMaterial());
        return material == null || material.isAir() || !material.isItem() ? Material.COMPASS : material;
    }

    /**
     * The same directory for a Bedrock player, as a native form.
     *
     * <p>The form was written with the Bedrock work and no command ever opened it, so a Bedrock
     * player had a directory nobody could reach. It draws the buttons off the same warps in the
     * same order, under the keys the operator already has.
     */
    private void openForm(
            BedrockFormService forms, Player player, List<Entry> entries, java.util.function.Consumer<Entry> onVisit) {
        List<IslandWarp> warps = new ArrayList<>(entries.size());
        Map<WarpKey, Entry> byWarp = new HashMap<>();
        for (Entry entry : entries) {
            warps.add(entry.warp());
            byWarp.put(keyOf(entry.warp()), entry);
        }
        forms.openWarpDirectoryForm(player, warps, warp -> {
            Entry chosen = byWarp.get(keyOf(warp));
            if (chosen != null) {
                onVisit.accept(chosen);
            }
        });
    }

    /** What makes one warp in the directory different from another: the island and the name. */
    private record WarpKey(IslandId islandId, String name) {}

    private static WarpKey keyOf(IslandWarp warp) {
        return new WarpKey(warp.islandId(), warp.name().value());
    }

    /** The owner name for each island in the list, resolved once per island rather than per warp. */
    public static List<Entry> entriesOf(List<IslandWarp> warps, java.util.function.Function<IslandId, String> owner) {
        Map<IslandId, String> names = new HashMap<>();
        List<Entry> entries = new ArrayList<>(warps.size());
        for (IslandWarp warp : warps) {
            entries.add(new Entry(
                    warp,
                    Optional.ofNullable(names.computeIfAbsent(warp.islandId(), owner))
                            .orElse("?")));
        }
        return entries;
    }
}
