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
}
