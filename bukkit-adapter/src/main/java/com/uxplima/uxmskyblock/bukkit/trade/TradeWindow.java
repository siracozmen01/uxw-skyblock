package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.runtime.MenuHolder;
import com.uxplima.uxmlib.menu.spec.ContentRegionSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import org.jspecify.annotations.Nullable;

/**
 * The window a trade is agreed in, one per player: their offer on the left, the other player's on the
 * right, and along the bottom their own button, the button that calls it off, and whether the other
 * player agrees.
 *
 * <p>Every click in it is the trade's to answer and none moves an item: a stack is offered by clicking
 * it in the player's own inventory below, and taken back by clicking it in the left half.
 */
final class TradeWindow {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(TradeWindow.class.getName());

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

    /** The menu file a trade is drawn from. */
    static final String FILE = "player-trade";

    /** The region of the file that holds the viewer's own offer. */
    static final String MINE_REGION = "skyblock:trade-mine";

    /** The region of the file that shows the other player's offer. */
    static final String THEIRS_REGION = "skyblock:trade-theirs";

    /** The verb that turns the viewer's agreement on or off. */
    static final String AGREE = "skyblock:trade-agree";

    /** The verb that calls the trade off. */
    static final String CALL_OFF = "skyblock:trade-cancel";

    /** The requirement that holds while the viewer agrees. */
    static final String AGREED = "skyblock:trade-agreed";

    /** The requirement that holds while the other player agrees. */
    static final String THEY_AGREED = "skyblock:trade-they-agreed";

    /**
     * What a window drawn from the file asks of the trade. The window built in code answers its own
     * clicks, so only a file needs this.
     */
    interface Moves {

        /** {@code player} offers the stack in {@code slot} of their own inventory. */
        void offer(Player player, Trade trade, int slot, ItemStack item);

        /** {@code player} takes back the {@code index}th stack they offered. */
        void withdraw(Player player, Trade trade, int index);

        /** {@code player} agrees, or takes their agreement back. */
        void agree(Player player, Trade trade);

        /** {@code player} calls the trade off. */
        void callOff(Player player, Trade trade);
    }

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
    private @Nullable SkyblockMenuEngine engine;

    /** Whether the file was found unable to hold a trade, said once rather than on every trade. */
    private final java.util.concurrent.atomic.AtomicBoolean saidTheFileCannotHoldATrade =
            new java.util.concurrent.atomic.AtomicBoolean();

    TradeWindow(Messages messages, TradeConfiguration.Window look) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.look = Objects.requireNonNull(look, "look");
    }

    /**
     * Draws a trade from {@code menus/player-trade.conf} from now on: the window, its buttons and where
     * each offer shows are the operator's, and the offers themselves stay on the trade. The window
     * built in code stays for a server whose file is gone or cannot hold a whole offer.
     */
    void useMenuEngine(SkyblockMenuEngine engine, Moves moves) {
        this.engine = Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(moves, "moves");
        engine.bindings().content(MINE_REGION, new TradeOffers(true, moves));
        engine.bindings().content(THEIRS_REGION, new TradeOffers(false, moves));
        engine.bindings().condition(AGREED, (ctx, args) -> agrees(ctx, true));
        engine.bindings().condition(THEY_AGREED, (ctx, args) -> agrees(ctx, false));
        engine.action(AGREE, ctx -> {
            View view = viewIn(ctx.context());
            if (view != null) {
                moves.agree(ctx.player(), view.trade());
            }
        });
        engine.action(CALL_OFF, ctx -> {
            View view = viewIn(ctx.context());
            if (view != null) {
                moves.callOff(ctx.player(), view.trade());
            }
        });
    }

    /** Whether the viewer, or the other player, agrees to the trade behind the window. */
    private static boolean agrees(MenuContext ctx, boolean viewer) {
        View view = viewIn(ctx);
        if (view == null) {
            return false;
        }
        return view.trade().ready(viewer ? view.viewer() : view.trade().other(view.viewer()));
    }

    /** The trade behind a window drawn from the file, or null for a window reopened without one. */
    static @Nullable View viewIn(MenuContext ctx) {
        return ctx.subjectRaw().orElse(null) instanceof View view ? view : null;
    }

    /** The trade window behind {@code top}, built in code or drawn from the file, or null. */
    static @Nullable View viewOf(@Nullable Inventory top) {
        if (top == null) {
            return null;
        }
        if (top.getHolder() instanceof View view) {
            return view;
        }
        return top.getHolder() instanceof MenuHolder menu ? viewIn(menu.ctx()) : null;
    }

    /** Whether the file can hold a trade: both offers, as many slots each as a player may offer. */
    private boolean fileHoldsATrade(SkyblockMenuEngine files) {
        Optional<MenuSpec> spec = files.spec(FILE);
        if (spec.isEmpty()) {
            return false;
        }
        ContentRegionSpec mine = spec.get().contents().get(MINE_REGION);
        ContentRegionSpec theirs = spec.get().contents().get(THEIRS_REGION);
        boolean holds = mine != null
                && theirs != null
                && mine.slots().slots().size() == Trade.MOST_OFFERS
                && theirs.slots().slots().size() == Trade.MOST_OFFERS
                && !mine.editable()
                && !theirs.editable();
        if (!holds && saidTheFileCannotHoldATrade.compareAndSet(false, true)) {
            LOGGER.warning(() -> "menus/" + FILE + ".conf needs the content regions \"" + MINE_REGION + "\" and \""
                    + THEIRS_REGION + "\", " + Trade.MOST_OFFERS + " slots each and not editable. Trades open in"
                    + " the window built in code until it has them.");
        }
        return holds;
    }

    /** Opens the trade for {@code viewer}, who trades with {@code otherName}. */
    void open(Player viewer, Trade trade, String otherName) {
        SkyblockMenuEngine files = this.engine;
        if (files != null
                && fileHoldsATrade(files)
                && files.openHolding(
                        viewer, FILE, new View(trade, viewer.getUniqueId()), java.util.Map.of("player", otherName))) {
            return;
        }
        Inventory window = Bukkit.createInventory(
                new View(trade, viewer.getUniqueId()),
                ROWS * 9,
                MenuTitles.centre(messages.renderPlain(
                        viewer, "trade.window.title", Placeholder.unparsed("argument_player", otherName))));
        draw(viewer, window, trade);
        viewer.openInventory(window);
    }

    /** Draws the trade as it stands again in {@code window}, wherever the window was drawn from. */
    void redraw(Player viewer, Inventory window, Trade trade) {
        SkyblockMenuEngine files = this.engine;
        if (files != null && window.getHolder() instanceof MenuHolder) {
            files.menus().redraw(viewer, FILE);
            return;
        }
        draw(viewer, window, trade);
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
            Component name = messages.renderPlain(viewer, key);
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }
}
