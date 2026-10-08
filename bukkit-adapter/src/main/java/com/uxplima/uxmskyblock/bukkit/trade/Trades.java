package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * Trading between players: asking, agreeing in the window, and carrying the trade out.
 *
 * <p>A player asks another with the trade command; the other answers with the same command naming the
 * first. Both then see the trade window. A request lasts as long as {@code modules/trade.conf} says. A
 * player trades with one other player at a time, and a player whose own items are kept aside on a
 * creative plot does not trade at all.
 *
 * <p>When both agree the trade is carried out off the players' threads, because it waits on the
 * database, holding both sessions' write locks so no checkpoint writes either inventory meanwhile.
 */
public final class Trades {

    private static final Logger LOGGER = Logger.getLogger(Trades.class.getName());

    /** A question one player asked another, and when it lapses. */
    private record Request(UUID from, long lapsesAt) {}

    /** How a trade reaches the rest of the plugin. */
    public interface Sessions {

        /** The player's session while it is in play here, or null. */
        @Nullable ActiveSession session(UUID player);

        /** Takes the player off the server, so that recovery and not what they hold decides. */
        void fence(UUID player, String why);
    }

    private final TradeConfiguration config;
    private final Messages messages;
    private final SchedulerPort scheduler;
    private final Sessions sessions;
    private final TradeExchange exchange;
    private final Predicate<Player> keptAside;
    private final LongSupplier nanoClock;
    private final TradeWindow window;
    private final OnThePlayersThread thread;
    private final Function<String, String> commandLine;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Trade> trades = new ConcurrentHashMap<>();

    public Trades(
            TradeConfiguration config,
            Messages messages,
            SchedulerPort scheduler,
            Sessions sessions,
            TradeExchange exchange,
            Predicate<Player> keptAside,
            LongSupplier nanoClock) {
        this.config = Objects.requireNonNull(config, "config");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.keptAside = Objects.requireNonNull(keptAside, "keptAside");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.window = new TradeWindow(messages, config.window());
        this.thread = new OnThePlayersThread(scheduler);
        this.commandLine = word -> messages.provider().commandLine(word);
    }

    /** Whether {@code player} is in a trade now. */
    public boolean trading(UUID player) {
        return trades.containsKey(player);
    }

    /**
     * {@code asker} asks {@code other} to trade, or answers their question. Runs on the asker's thread.
     */
    public void ask(Player asker, @Nullable Player other) {
        if (!config.enabled()) {
            messages.send(asker, "trade.disabled");
            return;
        }
        if (other == null || !other.isOnline()) {
            messages.send(asker, "error.player_not_found");
            return;
        }
        if (other.getUniqueId().equals(asker.getUniqueId())) {
            messages.send(asker, "trade.self");
            return;
        }
        String reason = whyNot(asker, other);
        if (reason != null) {
            messages.send(asker, reason, Placeholder.unparsed("player", other.getName()));
            return;
        }
        Request asked = requests.get(asker.getUniqueId());
        if (asked != null && asked.from().equals(other.getUniqueId()) && nanoClock.getAsLong() - asked.lapsesAt() < 0) {
            requests.remove(asker.getUniqueId());
            start(other, asker);
            return;
        }
        requests.put(
                other.getUniqueId(),
                new Request(asker.getUniqueId(), nanoClock.getAsLong() + config.requestSeconds() * 1_000_000_000L));
        messages.send(asker, "trade.asked", Placeholder.unparsed("player", other.getName()));
        String answer = commandLine.apply("trade") + " " + asker.getName();
        TagResolver accept = TagResolver.resolver("answer", Tag.styling(ClickEvent.runCommand(answer)));
        scheduler.onEntity(
                other.getUniqueId(),
                () -> messages.send(
                        other,
                        "trade.asks_you",
                        Placeholder.unparsed("player", asker.getName()),
                        Placeholder.unparsed("command", answer),
                        Placeholder.unparsed("seconds", Integer.toString(config.requestSeconds())),
                        accept));
    }

    /** The message key of why {@code asker} and {@code other} cannot trade now, or null when they can. */
    private @Nullable String whyNot(Player asker, Player other) {
        if (trading(asker.getUniqueId())) {
            return "trade.already";
        }
        if (trading(other.getUniqueId())) {
            return "trade.other_busy";
        }
        if (keptAside.test(asker)) {
            return "sealed.refused";
        }
        if (keptAside.test(other)) {
            return "trade.other_unavailable";
        }
        if (sessions.session(asker.getUniqueId()) == null || sessions.session(other.getUniqueId()) == null) {
            return "error.session_not_active";
        }
        int reach = config.maxDistance();
        if (reach >= 0 && !within(asker, other, reach)) {
            return "trade.too_far";
        }
        return null;
    }

    private static boolean within(Player asker, Player other, int reach) {
        org.bukkit.Location here = asker.getLocation();
        org.bukkit.Location there = other.getLocation();
        return here != null
                && there != null
                && asker.getWorld().equals(other.getWorld())
                && here.distanceSquared(there) <= (double) reach * reach;
    }

    private void start(Player first, Player second) {
        Trade trade = new Trade(first.getUniqueId(), second.getUniqueId());
        if (trades.putIfAbsent(first.getUniqueId(), trade) != null) {
            messages.send(second, "trade.other_busy", Placeholder.unparsed("player", first.getName()));
            return;
        }
        if (trades.putIfAbsent(second.getUniqueId(), trade) != null) {
            trades.remove(first.getUniqueId(), trade);
            messages.send(second, "trade.already");
            return;
        }
        for (Player viewer : List.of(first, second)) {
            Player other = viewer == first ? second : first;
            scheduler.onEntity(viewer.getUniqueId(), () -> window.open(viewer, trade, other.getName()));
        }
    }

    /** Answers a click in a trade window. Nothing in a trade window moves an item. */
    public void onClick(InventoryClickEvent event) {
        TradeWindow.View view = viewOf(event.getView());
        if (view == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        Trade trade = view.trade();
        UUID me = player.getUniqueId();
        int raw = event.getRawSlot();
        boolean changed;
        if (raw >= TradeWindow.ROWS * 9) {
            // Below the window is the player's own inventory, and the slot is one of its own.
            ItemStack clicked = player.getInventory().getItem(event.getSlot());
            changed = clicked != null && trade.offer(me, event.getSlot(), clicked);
        } else if (raw == TradeWindow.CANCEL) {
            callOff(trade, me);
            return;
        } else if (raw == TradeWindow.READY) {
            if (trade.toggleReady(me)) {
                redraw(trade);
                carryOut(trade);
                return;
            }
            changed = true;
        } else {
            changed = trade.withdraw(me, TradeWindow.mine(raw));
        }
        if (changed) {
            redraw(trade);
        }
    }

    /** A drag in a trade window would move items; it does nothing. */
    public void onDrag(InventoryDragEvent event) {
        if (viewOf(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    /** A player who closes the window calls the trade off. */
    public void onClose(InventoryCloseEvent event) {
        TradeWindow.View view = viewOf(event.getView());
        if (view != null) {
            callOff(view.trade(), event.getPlayer().getUniqueId());
        }
    }

    /** A player who leaves calls their trade off and forgets their requests. */
    public void onLeave(UUID player) {
        requests.remove(player);
        requests.values().removeIf(request -> request.from().equals(player));
        Trade trade = trades.get(player);
        if (trade != null) {
            callOff(trade, player);
        }
    }

    private void callOff(Trade trade, UUID by) {
        if (!trade.close()) {
            return;
        }
        forget(trade);
        for (UUID player : trade.players()) {
            scheduler.onEntity(player, () -> {
                Player online = Bukkit.getPlayer(player);
                if (online == null) {
                    return;
                }
                closeWindow(online, trade);
                Player caller = Bukkit.getPlayer(by);
                messages.send(
                        online,
                        "trade.called_off",
                        Placeholder.unparsed("player", caller == null ? "" : caller.getName()));
            });
        }
    }

    private void carryOut(Trade trade) {
        List<UUID> players = trade.players();
        scheduler.async(() -> {
            Result<InventoryMutationOperationId, String> done;
            try {
                done = exchangeLocked(trade, players);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "A trade between " + players + " failed", e);
                done = Result.err(TradeExchange.NOT_SAVED);
            }
            Result<InventoryMutationOperationId, String> outcome = done;
            if (outcome.isOk()) {
                trade.finish();
                forget(trade);
            } else {
                trade.reopen();
            }
            for (UUID player : players) {
                scheduler.onEntity(player, () -> told(player, trade, outcome));
            }
        });
    }

    private Result<InventoryMutationOperationId, String> exchangeLocked(Trade trade, List<UUID> players) {
        List<ActiveSession> held = new ArrayList<>(players.size());
        for (UUID player : players) {
            ActiveSession session = sessions.session(player);
            if (session == null) {
                return Result.err("trade.left");
            }
            held.add(session);
        }
        // Locked in one order, so two trades over the same players never wait on each other.
        List<ReentrantLock> locks = held.stream()
                .sorted(Comparator.comparing(session -> session.playerUuid().value()))
                .map(ActiveSession::writes)
                .toList();
        locks.forEach(ReentrantLock::lock);
        try {
            List<PlayerTradeSide> sides = new ArrayList<>(players.size());
            for (int i = 0; i < players.size(); i++) {
                UUID player = players.get(i);
                Player online = Bukkit.getPlayer(player);
                if (online == null) {
                    return Result.err("trade.left");
                }
                UUID other = trade.other(player);
                sides.add(new PlayerTradeSide(
                        online,
                        held.get(i),
                        trade.offers(player),
                        trade.offers(other).stream().map(Trade.Offer::item).toList(),
                        thread,
                        () -> sessions.fence(player, "a trade nobody can tell was written")));
            }
            return exchange.exchange(sides);
        } finally {
            locks.forEach(ReentrantLock::unlock);
        }
    }

    private void told(UUID player, Trade trade, Result<InventoryMutationOperationId, String> outcome) {
        Player online = Bukkit.getPlayer(player);
        if (online == null) {
            return;
        }
        Player other = Bukkit.getPlayer(trade.other(player));
        TagResolver name = Placeholder.unparsed("player", other == null ? "" : other.getName());
        if (outcome.isOk()) {
            closeWindow(online, trade);
            messages.send(online, "trade.done", name);
            return;
        }
        messages.send(online, outcome.errorOrThrow(), name);
        Inventory top = windowOf(online, trade);
        if (top != null) {
            window.draw(online, top, trade);
        }
    }

    private void redraw(Trade trade) {
        for (UUID player : trade.players()) {
            scheduler.onEntity(player, () -> {
                Player online = Bukkit.getPlayer(player);
                if (online == null) {
                    return;
                }
                Inventory top = windowOf(online, trade);
                if (top != null) {
                    window.draw(online, top, trade);
                }
            });
        }
    }

    private static void closeWindow(Player player, Trade trade) {
        if (windowOf(player, trade) != null) {
            player.closeInventory();
        }
    }

    /** The trade window {@code view} shows, or null when it shows something else. */
    private static TradeWindow.@Nullable View viewOf(org.bukkit.inventory.InventoryView view) {
        @Nullable Inventory top = view.getTopInventory();
        return top != null && top.getHolder() instanceof TradeWindow.View trade ? trade : null;
    }

    /** The window of {@code trade} {@code player} has open, or null. */
    private static @Nullable Inventory windowOf(Player player, Trade trade) {
        @Nullable Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof TradeWindow.View view && view.trade() == trade ? top : null;
    }

    private void forget(Trade trade) {
        for (UUID player : trade.players()) {
            trades.remove(player, trade);
        }
    }
}
