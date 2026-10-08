package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the TradeWinds vessels are kept. */
public interface VesselsPort {

    /** Every TradeWinds vessel. */
    Set<IslandId> findAll();

    /** Whether the island is a TradeWinds vessel. */
    boolean exists(IslandId islandId);

    /** Records an island as a TradeWinds vessel. A second record changes nothing. */
    void add(IslandId islandId);

    /**
     * The vessel's cargo hold as durable storage holds it, or empty when the island is no vessel. A hold
     * nothing was ever put in is no bytes at version 1.
     */
    java.util.Optional<Cargo> cargo(IslandId islandId);

    /** A vessel's cargo hold: its serialised items and the version every change moves. */
    @SuppressWarnings("ArrayRecordComponent")
    record Cargo(byte[] items, long version) {
        public Cargo {
            items = java.util.Arrays.copyOf(java.util.Objects.requireNonNull(items, "items"), items.length);
        }

        @Override
        public byte[] items() {
            return java.util.Arrays.copyOf(items, items.length);
        }
    }
}
