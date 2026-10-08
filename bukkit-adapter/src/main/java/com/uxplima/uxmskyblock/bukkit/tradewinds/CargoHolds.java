package com.uxplima.uxmskyblock.bukkit.tradewinds;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.trade.OnThePlayersThread;
import com.uxplima.uxmskyblock.bukkit.trade.PlayerTradeSide;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoTransfer;
import com.uxplima.uxmskyblock.core.application.tradewinds.Ranks;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * The cargo holds of TradeWinds vessels: a chest window anyone of a vessel's team aboard it opens with
 * {@code /is cargo}.
 *
 * <p>A click on a stack in the hold takes it into the player's inventory, and a click on a stack in the
 * player's own inventory puts it into the hold. Nothing else moves an item in the window. Every move is
 * one operation across two owners, the player and the vessel, carried out through the
 * {@link CargoTransfer} under the player's write lock and the vessel's lease. The hold lives in durable
 * storage and is read fresh for every move, so two players of one team never move the same stack.
 *
 * <p>While a move is carried out its player's inventory holds still: {@link #moving} names them for a
 * {@link com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener}.
 */
public final class CargoHolds {

    private static final Logger LOGGER = Logger.getLogger(CargoHolds.class.getName());

    /** Whose session a move is written under, and how a player is taken off the server. */
    public interface Sessions {

        /** The session {@code player} plays under here, or null when they may not move items now. */
        @Nullable ActiveSession session(UUID player);

        /** Takes {@code player} off the server, so recovery settles what a move left open. */
        void fence(UUID player, String why);
    }

    /** The window a hold is shown in, holding which vessel it shows. */
    public static final class View implements InventoryHolder {

        private final IslandId vessel;
        private @Nullable Inventory inventory;

        View(IslandId vessel) {
            this.vessel = vessel;
        }

        public IslandId vessel() {
            return vessel;
        }

        @Override
        public Inventory getInventory() {
            return Objects.requireNonNull(inventory, "the window is not open yet");
        }
    }

    private final VesselService vessels;
    private final VesselsPort holds;
    private final VesselLease lease;
    private final CargoTransfer transfer;
    private final Function<Location, Optional<Island>> islandAt;
    private final Sessions sessions;
    private final SchedulerPort scheduler;
    private final Messages messages;
    private final Predicate<Player> sealed;
    private final Function<IslandId, Ranks.Rank> rankOf;
    private final OnThePlayersThread thread;
    private final Crew crew;
    private final Set<UUID> moving = ConcurrentHashMap.newKeySet();
    private final Map<IslandId, Set<UUID>> viewers = new ConcurrentHashMap<>();

    @SuppressWarnings("TooManyParameters")
    public CargoHolds(
            VesselService vessels,
            VesselsPort holds,
            VesselLease lease,
            CargoTransfer transfer,
            Function<Location, Optional<Island>> islandAt,
            Sessions sessions,
            SchedulerPort scheduler,
            Messages messages,
            Predicate<Player> sealed,
            Function<IslandId, Ranks.Rank> rankOf) {
        this.vessels = Objects.requireNonNull(vessels, "vessels");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.islandAt = Objects.requireNonNull(islandAt, "islandAt");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.sealed = Objects.requireNonNull(sealed, "sealed");
        this.rankOf = Objects.requireNonNull(rankOf, "rankOf");
        this.thread = new OnThePlayersThread(scheduler);
        this.crew = new Crew(vessels, islandAt, sessions);
    }

    /** Whether a move of {@code player}'s is being carried out now, when nothing may change their inventory. */
    public boolean moving(UUID player) {
        return moving.contains(player);
    }

    /** Opens the hold of the vessel {@code player} stands on, when they are of its team. */
    public void open(Player player) {
        Location at = player.getLocation();
        Optional<Island> island = at == null ? Optional.empty() : islandAt.apply(at);
        if (island.isEmpty() || !vessels.isVessel(island.get().id())) {
            messages.send(player, "tradewinds.hold.not_vessel");
            return;
        }
        if (sealed.test(player)) {
            messages.send(player, "tradewinds.hold.sealed");
            return;
        }
        IslandId vessel = island.get().id();
        Result<IslandId, String> allowed = crew.aboard(player, vessel, IslandPermission.VAULT_VIEW);
        if (!allowed.isOk()) {
            messages.send(player, allowed.errorOrThrow());
            return;
        }
        PlayerUuid who = PlayerUuid.of(player.getUniqueId());
        scheduler.async(() -> {
            Optional<VesselsPort.Cargo> cargo;
            Ranks.Rank found;
            try {
                cargo = holds.cargo(vessel);
                found = rankOf.apply(vessel);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The cargo hold of vessel " + vessel + " could not be read.");
                scheduler.onEntity(who, () -> messages.send(player, "tradewinds.hold.busy"));
                return;
            }
            Optional<VesselsPort.Cargo> read = cargo;
            Ranks.Rank rank = found;
            scheduler.onEntity(who, () -> {
                if (read.isEmpty()) {
                    messages.send(player, "tradewinds.hold.busy");
                    return;
                }
                View view = new View(vessel);
                Inventory window = Bukkit.createInventory(
                        view,
                        rank.holdSlots(),
                        messages.renderPlain(
                                player,
                                "tradewinds.hold.title",
                                Names.of(messages, player, "rank", "ranks", rank.id())));
                view.inventory = window;
                window.setContents(window(read.get().items(), rank.holdSlots()));
                viewers.computeIfAbsent(vessel, key -> ConcurrentHashMap.newKeySet())
                        .add(player.getUniqueId());
                player.openInventory(window);
            });
        });
    }

    /** Answers a click in a hold window: a stack moves between the hold and the player, nothing else. */
    public void onClick(InventoryClickEvent event) {
        View view = viewOf(event.getView());
        if (view == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || moving(player.getUniqueId())) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        if (sealed.test(player)) {
            // What a creative place made never goes into a hold, and nothing from a hold goes into one.
            messages.send(player, "tradewinds.hold.sealed");
            return;
        }
        int slot = event.getSlot();
        boolean taking = clicked.getHolder() instanceof View;
        // Asked again on every click: a window stays open after its player left the crew or the vessel.
        Result<IslandId, String> allowed = crew.aboard(
                player, view.vessel(), taking ? IslandPermission.VAULT_WITHDRAW : IslandPermission.VAULT_DEPOSIT);
        if (!allowed.isOk()) {
            messages.send(player, allowed.errorOrThrow());
            if (!"tradewinds.hold.not_allowed".equals(allowed.errorOrThrow())) {
                player.closeInventory();
            }
            return;
        }
        if (taking) {
            ItemStack shown = clicked.getItem(slot);
            if (shown != null && !shown.getType().isAir()) {
                move(player, view.vessel(), Direction.TAKE, slot, shown.clone());
            }
            return;
        }
        if (slot < 0 || slot >= PlayerTradeSide.STORAGE_SLOTS) {
            return;
        }
        ItemStack carried = player.getInventory().getItem(slot);
        if (carried == null || carried.getType().isAir()) {
            return;
        }
        move(player, view.vessel(), Direction.PUT, slot, carried.clone());
    }

    /** A drag in a hold window would move items; it does nothing. */
    public void onDrag(InventoryDragEvent event) {
        if (viewOf(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    /** A player who closes a hold window no longer sees it change. */
    public void onClose(InventoryCloseEvent event) {
        View view = viewOf(event.getView());
        if (view != null) {
            forget(view.vessel(), event.getPlayer().getUniqueId());
        }
    }

    /** A player who leaves sees no hold. */
    public void onLeave(UUID player) {
        viewers.values().forEach(watching -> watching.remove(player));
    }

    private enum Direction {
        PUT,
        TAKE
    }

    private void move(Player player, IslandId vessel, Direction direction, int slot, ItemStack item) {
        UUID who = player.getUniqueId();
        if (!moving.add(who)) {
            return;
        }
        scheduler.async(() -> {
            Result<InventoryMutationOperationId, String> done;
            try {
                done = moveLocked(player, vessel, direction, slot, item);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "A cargo move of " + who + " on vessel " + vessel + " failed", e);
                done = Result.err(TradeExchange.NOT_SAVED);
            } finally {
                moving.remove(who);
            }
            Result<InventoryMutationOperationId, String> outcome = done;
            if (!outcome.isOk()) {
                scheduler.onEntity(PlayerUuid.of(who), () -> messages.send(player, keyOf(outcome.errorOrThrow())));
            }
            redraw(vessel);
        });
    }

    private Result<InventoryMutationOperationId, String> moveLocked(
            Player player, IslandId vessel, Direction direction, int slot, ItemStack item) {
        UUID who = player.getUniqueId();
        ActiveSession session = sessions.session(who);
        if (session == null) {
            return Result.err("trade.left");
        }
        ReentrantLock writes = session.writes();
        writes.lock();
        try {
            OptionalLong epoch = lease.hold(vessel);
            if (epoch.isEmpty()) {
                return Result.err("tradewinds.hold.elsewhere");
            }
            Optional<VesselsPort.Cargo> cargo = holds.cargo(vessel);
            if (cargo.isEmpty()) {
                return Result.err("tradewinds.hold.not_vessel");
            }
            byte[] stored = cargo.get().items();
            ItemStack[] hold = stacks(stored, rankOf.apply(vessel).holdSlots());
            List<PlayerTradeSide.Given> gives;
            List<ItemStack> receives;
            CargoTransfer.Cargo after;
            if (direction == Direction.PUT) {
                gives = List.of(new PlayerTradeSide.Given(slot, item));
                receives = List.of();
                after = snapshot -> {
                    ItemStack[] filled = copy(hold);
                    return stow(filled, item.clone())
                            ? Result.ok(BukkitInventorySerializer.serializeItemStacks(filled))
                            : Result.err("tradewinds.hold.full");
                };
            } else {
                ItemStack there = slot < hold.length ? hold[slot] : null;
                if (there == null || !there.isSimilar(item) || there.getAmount() != item.getAmount()) {
                    return Result.err("tradewinds.hold.moved");
                }
                gives = List.of();
                receives = List.of(there.clone());
                after = snapshot -> {
                    ItemStack[] emptied = copy(hold);
                    emptied[slot] = null;
                    return Result.ok(BukkitInventorySerializer.serializeItemStacks(emptied));
                };
            }
            PlayerTradeSide side = new PlayerTradeSide(
                    player,
                    session,
                    gives,
                    receives,
                    thread,
                    () -> sessions.fence(who, "a cargo move nobody can tell was written"));
            return transfer.move(
                    side,
                    new CargoJournalPort.Hold(vessel, epoch.getAsLong()),
                    cargo.get().version(),
                    stored,
                    after);
        } finally {
            writes.unlock();
        }
    }

    private void redraw(IslandId vessel) {
        Set<UUID> watching = viewers.get(vessel);
        if (watching == null || watching.isEmpty()) {
            return;
        }
        Optional<VesselsPort.Cargo> cargo;
        try {
            cargo = holds.cargo(vessel);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "The cargo hold of vessel " + vessel + " could not be read.");
            return;
        }
        if (cargo.isEmpty()) {
            return;
        }
        byte[] items = cargo.get().items();
        for (UUID viewer : List.copyOf(watching)) {
            scheduler.onEntity(PlayerUuid.of(viewer), () -> {
                Player online = Bukkit.getPlayer(viewer);
                if (online == null) {
                    forget(vessel, viewer);
                    return;
                }
                Inventory top = online.getOpenInventory().getTopInventory();
                if (top != null
                        && top.getHolder() instanceof View view
                        && view.vessel().equals(vessel)) {
                    top.setContents(window(items, top.getSize()));
                } else {
                    forget(vessel, viewer);
                }
            });
        }
    }

    private void forget(IslandId vessel, UUID player) {
        Set<UUID> watching = viewers.get(vessel);
        if (watching != null) {
            watching.remove(player);
        }
    }

    /** The hold's items, at least {@code slots} of them: more when it holds more than its rank shows. */
    private static ItemStack[] stacks(byte[] stored, int slots) {
        ItemStack[] items =
                stored.length == 0 ? new ItemStack[0] : BukkitInventorySerializer.deserializeItemStacks(stored);
        return items.length >= slots ? items : Arrays.copyOf(items, slots);
    }

    /** The hold's items as a window of {@code size} slots shows them. */
    private static ItemStack[] window(byte[] stored, int size) {
        return Arrays.copyOf(stacks(stored, size), size);
    }

    /** The message key of a move that did not happen: a trade's reasons are the hold's own. */
    static String keyOf(String why) {
        return why.startsWith("trade.") ? "tradewinds.hold." + why.substring("trade.".length()) : why;
    }

    /** Stows {@code item} in the hold, onto its own kind first, and says whether all of it fit. */
    static boolean stow(ItemStack[] hold, ItemStack item) {
        int remaining = item.getAmount();
        int most = item.getMaxStackSize();
        for (int i = 0; i < hold.length && remaining > 0; i++) {
            ItemStack slot = hold[i];
            if (slot != null && slot.isSimilar(item) && slot.getAmount() < most) {
                int moved = Math.min(remaining, most - slot.getAmount());
                slot.setAmount(slot.getAmount() + moved);
                remaining -= moved;
            }
        }
        for (int i = 0; i < hold.length && remaining > 0; i++) {
            if (hold[i] == null || hold[i].getType().isAir()) {
                ItemStack placed = item.clone();
                placed.setAmount(Math.min(remaining, most));
                hold[i] = placed;
                remaining -= placed.getAmount();
            }
        }
        return remaining == 0;
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static @Nullable View viewOf(org.bukkit.inventory.InventoryView view) {
        @Nullable Inventory top = view.getTopInventory();
        return top != null && top.getHolder() instanceof View hold ? hold : null;
    }
}
