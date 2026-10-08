package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A port the operator wrote: how long a voyage to it takes and what its market trades.
 *
 * @param id the key the operator gave it, and the key of its name in the language files
 * @param voyageSeconds how long a voyage to this port takes
 * @param goods what its market buys and sells, in the order the operator wrote them
 */
public record Port(String id, int voyageSeconds, List<Good> goods) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");

    /** The longest voyage, a day. */
    public static final int MAX_VOYAGE_SECONDS = 86_400;

    public Port {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("a port's key is from 1 to 64 of a-z, 0-9, _ and -: " + id);
        }
        if (voyageSeconds < 0 || voyageSeconds > MAX_VOYAGE_SECONDS) {
            throw new IllegalArgumentException("a voyage takes from 0 to " + MAX_VOYAGE_SECONDS + " seconds");
        }
        goods = List.copyOf(goods);
        if (goods.stream().map(Good::item).distinct().count() != goods.size()) {
            throw new IllegalArgumentException("port " + id + " names an item twice");
        }
    }

    /** The good of this market that is {@code item}, if it trades it. */
    public Optional<Good> good(String item) {
        return goods.stream().filter(good -> good.item().equals(item)).findFirst();
    }

    /**
     * One good of a market, priced per item in the bank's minor units.
     *
     * @param item the item, by the name the server knows it under
     * @param lot how many items one click buys or sells
     * @param pays what the port pays for one item the crew sells it, or 0 when it does not buy it
     * @param asks what the port asks for one item the crew buys from it, or 0 when it does not sell it
     */
    public record Good(String item, int lot, long pays, long asks) {
        public Good {
            Objects.requireNonNull(item, "item");
            if (lot < 1 || lot > 64 * 36) {
                throw new IllegalArgumentException("a lot is from 1 to " + 64 * 36 + " items");
            }
            if (pays < 0 || asks < 0) {
                throw new IllegalArgumentException("a price is not below 0");
            }
            if (pays == 0 && asks == 0) {
                throw new IllegalArgumentException(item + " is neither bought nor sold");
            }
            if (asks > 0 && pays >= asks) {
                // Otherwise buying a good and selling it back would cost nothing, or make money from nowhere.
                throw new IllegalArgumentException(item + " is paid for as much as it is asked for, or more");
            }
        }

        /** Whether the port buys this good from the crew. */
        public boolean bought() {
            return pays > 0;
        }

        /** Whether the port sells this good to the crew. */
        public boolean sold() {
            return asks > 0;
        }
    }
}
