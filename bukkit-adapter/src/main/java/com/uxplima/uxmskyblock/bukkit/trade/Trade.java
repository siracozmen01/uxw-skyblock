package com.uxplima.uxmskyblock.bukkit.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/**
 * One trade between two players while they agree on it: what each offers and whether each agrees.
 *
 * <p>An offer names a slot of its owner's inventory and the stack it held when it was offered. Nothing
 * leaves the inventory until the trade happens, so a trade called off, a player who leaves and a server
 * that stops all leave every item where it was. Any change to either offer takes back both agreements:
 * nobody agrees to an offer they have not seen.
 *
 * <p>Both players' threads reach a trade, so every method holds its lock.
 */
final class Trade {

    /** The most stacks one player offers in one trade: the slots of their half of the window. */
    static final int MOST_OFFERS = 20;

    /** Where a trade is. */
    enum Stage {
        /** The players are agreeing on it. */
        OPEN,
        /** Both agreed and it is being carried out; nothing changes it now. */
        EXCHANGING,
        /** It happened or was called off. */
        CLOSED
    }

    /** A stack offered from {@code slot} of its owner's inventory, as it was when offered. */
    record Offer(int slot, ItemStack item) {
        Offer {
            Objects.requireNonNull(item, "item");
            item = item.clone();
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }

    private static final class Side {
        private final UUID player;
        private final List<Offer> offers = new ArrayList<>();
        private boolean ready;

        Side(UUID player) {
            this.player = player;
        }
    }

    private final Side first;
    private final Side second;
    private Stage stage = Stage.OPEN;

    Trade(UUID first, UUID second) {
        if (Objects.requireNonNull(first, "first").equals(Objects.requireNonNull(second, "second"))) {
            throw new IllegalArgumentException("A player does not trade with themselves");
        }
        this.first = new Side(first);
        this.second = new Side(second);
    }

    synchronized boolean involves(UUID player) {
        return first.player.equals(player) || second.player.equals(player);
    }

    synchronized UUID other(UUID player) {
        return side(player) == first ? second.player : first.player;
    }

    synchronized List<UUID> players() {
        return List.of(first.player, second.player);
    }

    synchronized Stage stage() {
        return stage;
    }

    synchronized List<Offer> offers(UUID player) {
        return List.copyOf(side(player).offers);
    }

    synchronized boolean ready(UUID player) {
        return side(player).ready;
    }

    /**
     * Offers the stack in {@code slot} of {@code player}'s inventory. A slot is offered once, and an
     * empty one never.
     */
    synchronized boolean offer(UUID player, int slot, ItemStack item) {
        Side side = side(player);
        if (stage != Stage.OPEN
                || item.getType().isAir()
                || side.offers.size() >= MOST_OFFERS
                || side.offers.stream().anyMatch(offer -> offer.slot() == slot)) {
            return false;
        }
        side.offers.add(new Offer(slot, item));
        changed();
        return true;
    }

    /** Takes back the {@code index}th stack {@code player} offered. */
    synchronized boolean withdraw(UUID player, int index) {
        Side side = side(player);
        if (stage != Stage.OPEN || index < 0 || index >= side.offers.size()) {
            return false;
        }
        side.offers.remove(index);
        changed();
        return true;
    }

    /**
     * Turns {@code player}'s agreement on or off, and says whether both now agree. Agreeing to a trade
     * where nobody offers anything does nothing.
     */
    synchronized boolean toggleReady(UUID player) {
        if (stage != Stage.OPEN || (first.offers.isEmpty() && second.offers.isEmpty())) {
            return false;
        }
        Side side = side(player);
        side.ready = !side.ready;
        if (first.ready && second.ready) {
            stage = Stage.EXCHANGING;
            return true;
        }
        return false;
    }

    /** The trade could not be carried out: both are asked to agree again to what is on the table. */
    synchronized void reopen() {
        if (stage == Stage.EXCHANGING) {
            stage = Stage.OPEN;
            changed();
        }
    }

    /** Ends the trade, and says whether this call ended it. One that is being carried out goes on. */
    synchronized boolean close() {
        if (stage == Stage.OPEN) {
            stage = Stage.CLOSED;
            return true;
        }
        return false;
    }

    /** The trade was carried out. */
    synchronized void finish() {
        stage = Stage.CLOSED;
    }

    private void changed() {
        first.ready = false;
        second.ready = false;
    }

    private Side side(UUID player) {
        if (first.player.equals(player)) {
            return first;
        }
        if (second.player.equals(player)) {
            return second;
        }
        throw new IllegalArgumentException(player + " is not in this trade");
    }
}
