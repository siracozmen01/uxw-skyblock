package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * The window a trade is agreed in, one per player: their offer on the left, the other player's on the
 * right, and along the bottom their own button, the button that calls it off, and whether the other
 * player agrees.
 *
 * <p>Every click in it is the trade's to answer and none moves an item: a stack is offered by clicking
 * it in the player's own inventory below, and taken back by clicking it in the left half.
 */
final class TradeWindow {

    /** The rows of the window. */
    static final int ROWS = 6;

    /** The slots of the viewer's own offer, the left half. */
    static final int[] MINE = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30, 36, 37, 38, 39};

    /** The slots of the other player's offer, the right half. */
    static final int[] THEIRS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35, 41, 42, 43, 44};

    /** The column between the halves and the gaps along the bottom. */
    static final int[] DIVIDER = {4, 13, 22, 31, 40, 46, 47, 48, 50, 51, 52};

    /** The viewer's own button. */
    static final int READY = 45;

    /** The button that calls the trade off. */
    static final int CANCEL = 49;

    /** Whether the other player agrees. */
    static final int OTHER = 53;

    /** Marks a trade window and says whose it is. */
    record View(Trade trade, UUID viewer) implements InventoryHolder {
        View {
            Objects.requireNonNull(trade, "trade");
            Objects.requireNonNull(viewer, "viewer");
        }

        @Override
        public Inventory getInventory() {
            throw new UnsupportedOperationException("The holder is a marker; the inventory owns it, not the reverse.");
        }
    }

    private final Messages messages;
    private final TradeConfiguration.Window look;

    TradeWindow(Messages messages, TradeConfiguration.Window look) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.look = Objects.requireNonNull(look, "look");
    }

    /** Opens the trade for {@code viewer}, who trades with {@code otherName}. */
    void open(Player viewer, Trade trade, String otherName) {
        Inventory window = Bukkit.createInventory(
                new View(trade, viewer.getUniqueId()),
                ROWS * 9,
                messages.render(viewer, "trade.window.title", Placeholder.unparsed("player", otherName)));
        draw(viewer, window, trade);
        viewer.openInventory(window);
    }

    /** Draws the trade as it stands into {@code window}, as {@code viewer} sees it. */
    void draw(Player viewer, Inventory window, Trade trade) {
        UUID me = viewer.getUniqueId();
        UUID them = trade.other(me);
        fill(window, MINE, trade.offers(me));
        fill(window, THEIRS, trade.offers(them));
        for (int slot : DIVIDER) {
            window.setItem(slot, named(viewer, look.divider(), "trade.window.divider"));
        }
        window.setItem(
                READY,
                trade.ready(me)
                        ? named(viewer, look.ready(), "trade.window.ready")
                        : named(viewer, look.waiting(), "trade.window.waiting"));
        window.setItem(CANCEL, named(viewer, look.cancel(), "trade.window.cancel"));
        window.setItem(
                OTHER,
                trade.ready(them)
                        ? named(viewer, look.otherReady(), "trade.window.other_ready")
                        : named(viewer, look.otherWaiting(), "trade.window.other_waiting"));
    }

    /** Which of the viewer's offers {@code slot} shows, or -1 when it shows none of theirs. */
    static int mine(int slot) {
        for (int i = 0; i < MINE.length; i++) {
            if (MINE[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static void fill(Inventory window, int[] slots, List<Trade.Offer> offers) {
        for (int i = 0; i < slots.length; i++) {
            window.setItem(slots[i], i < offers.size() ? offers.get(i).item() : null);
        }
    }

    private ItemStack named(Player viewer, Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component name = messages.render(viewer, key);
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}
