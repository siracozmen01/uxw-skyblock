package com.uxplima.uxmskyblock.core.application.boxed;

import java.util.Map;
import java.util.OptionalLong;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** Where the Boxed islands and the advancements each has earned are kept. */
public interface BoxedIslandsPort {

    /** Every Boxed island and the blocks its advancements have earned it. */
    Map<IslandId, Long> findAll();

    /** One island's earned blocks, or empty when it is not a Boxed island. */
    OptionalLong find(IslandId islandId);

    /** Records an island as a Boxed island. A second record changes nothing. */
    void add(IslandId islandId);

    /**
     * Records an advancement the island earned, worth {@code blocks}. Returns false when the island had
     * earned it already, which changes nothing.
     */
    boolean earn(IslandId islandId, String advancement, int blocks);
}
