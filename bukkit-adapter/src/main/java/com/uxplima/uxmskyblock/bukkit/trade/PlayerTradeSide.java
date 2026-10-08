package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.jspecify.annotations.Nullable;

/**
 * One player's inventory in a journaled exchange, a trade or a move into a vessel's hold: what they give
 * from their own slots and what they receive.
 *
 * <p>The inventory is read, changed and put back on the player's own thread, serialised the way a
 * checkpoint writes it, so the fingerprints the journal records are the ones recovery finds in durable
 * storage. What the player receives goes into their storage slots only, never into armour.
 */
public final class PlayerTradeSide implements TradeExchange.LiveSide {

    /** The slots of the inventory that hold items a player carries: the hotbar and the main grid. */
    public static final int STORAGE_SLOTS = 36;

    /** What the player gives: {@code item} out of their slot {@code slot}. */
    public record Given(int slot, ItemStack item) {
        public Given {
            Objects.requireNonNull(item, "item");
            item = item.clone();
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }

    private final Player player;
    private final ActiveSession session;
    private final long expectedVersion;
    private final List<Given> gives;
    private final List<ItemStack> receives;
    private final OnThePlayersThread thread;
    private final Runnable fence;
    private ItemStack @Nullable [] before;
    private ItemStack @Nullable [] after;

    public PlayerTradeSide(
            Player player,
            ActiveSession session,
            List<Given> gives,
            List<ItemStack> receives,
            OnThePlayersThread thread,
            Runnable fence) {
        this.player = Objects.requireNonNull(player, "player");
        this.session = Objects.requireNonNull(session, "session");
        this.expectedVersion = session.lastDurableVersion();
        this.gives = List.copyOf(gives);
        this.receives = receives.stream().map(ItemStack::clone).toList();
        this.thread = Objects.requireNonNull(thread, "thread");
        this.fence = Objects.requireNonNull(fence, "fence");
    }

    @Override
    public TradeJournalPort.Holder holder() {
        return new TradeJournalPort.Holder(
                PlayerUuid.of(player.getUniqueId()), session.activeProfileId(), session.sessionEpoch());
    }

    @Override
    public long expectedVersion() {
        return expectedVersion;
    }

    @Override
    public Result<TradeExchange.Snapshot, String> read() {
        return thread.run(player.getUniqueId(), this::readHere, Result.err("trade.left"));
    }

    private Result<TradeExchange.Snapshot, String> readHere() {
        if (!player.isOnline()) {
            return Result.err("trade.left");
        }
        ItemStack[] now = copy(player.getInventory().getContents());
        ItemStack[] traded = copy(now);
        for (Given offer : gives) {
            ItemStack held = offer.slot() < traded.length ? traded[offer.slot()] : null;
            ItemStack offered = offer.item();
            if (held == null || !held.isSimilar(offered) || held.getAmount() < offered.getAmount()) {
                return Result.err("trade.offer_gone");
            }
            int left = held.getAmount() - offered.getAmount();
            if (left == 0) {
                traded[offer.slot()] = null;
            } else {
                held.setAmount(left);
            }
        }
        for (ItemStack item : receives) {
            if (!putInto(traded, item.clone())) {
                return Result.err("trade.no_room");
            }
        }
        this.before = now;
        this.after = traded;
        return Result.ok(new TradeExchange.Snapshot(
                BukkitInventorySerializer.serializeItemStacks(now),
                BukkitInventorySerializer.serializeItemStacks(traded)));
    }

    @Override
    public boolean apply(TradeExchange.Snapshot snapshot) {
        ItemStack[] traded = after;
        if (traded == null) {
            return false;
        }
        return thread.run(
                player.getUniqueId(),
                () -> {
                    if (!player.isOnline()
                            || !Arrays.equals(
                                    BukkitInventorySerializer.serializeItemStacks(
                                            player.getInventory().getContents()),
                                    snapshot.before())) {
                        return false;
                    }
                    player.getInventory().setContents(copy(traded));
                    player.updateInventory();
                    return true;
                },
                false);
    }

    /**
     * Puts the side back, but only from exactly what the trade left it. Anything else means the player
     * moved something since, and writing the whole inventory back over it could hand them twice what
     * they moved: they are taken off the server instead, and durable storage, which holds the inventory
     * as the trade found it, is what they come back to.
     */
    @Override
    public void putBack(TradeExchange.Snapshot snapshot) {
        ItemStack[] found = before;
        if (found == null) {
            return;
        }
        boolean putBack = thread.run(
                player.getUniqueId(),
                () -> {
                    if (!player.isOnline()) {
                        // Gone: nothing is in play, and what they come back to is durable storage.
                        return true;
                    }
                    if (!Arrays.equals(
                            BukkitInventorySerializer.serializeItemStacks(
                                    player.getInventory().getContents()),
                            snapshot.after())) {
                        return false;
                    }
                    player.getInventory().setContents(copy(found));
                    player.updateInventory();
                    return true;
                },
                false);
        if (!putBack) {
            fence.run();
        }
    }

    @Override
    public void durableAt(long version) {
        session.setLastDurableVersion(version);
    }

    @Override
    public void inDoubt() {
        fence.run();
    }

    /** Puts {@code item} into the storage slots, onto its own kind first, and says whether all of it fit. */
    private static boolean putInto(ItemStack[] contents, ItemStack item) {
        int remaining = item.getAmount();
        int most = item.getMaxStackSize();
        int slots = Math.min(STORAGE_SLOTS, contents.length);
        for (int i = 0; i < slots && remaining > 0; i++) {
            ItemStack slot = contents[i];
            if (slot != null && slot.isSimilar(item) && slot.getAmount() < most) {
                int moved = Math.min(remaining, most - slot.getAmount());
                slot.setAmount(slot.getAmount() + moved);
                remaining -= moved;
            }
        }
        for (int i = 0; i < slots && remaining > 0; i++) {
            if (contents[i] == null || contents[i].getType().isAir()) {
                ItemStack placed = item.clone();
                placed.setAmount(Math.min(remaining, most));
                contents[i] = placed;
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
}
