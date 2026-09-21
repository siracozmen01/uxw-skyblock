package com.uxplima.uxmskyblock.core.application.island;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * One place that forgets an island.
 *
 * <p>Half a dozen services keep something per island in memory: the upgrade tiers, the block and
 * spawner counts, the material index the worth is computed from, the dimensions that have been
 * generated, the quarantine. Several of them were written with a method to forget one and nothing
 * ever called it, and the material index had no such method at all.
 *
 * <p>An island id is a fresh uuid every time, so a stale entry is never read again and never gives
 * a wrong answer. It is simply never released either. A server that has made a hundred thousand
 * islands over a year is holding a hundred thousand material indexes for islands that no longer
 * exist, and a material index is a count per material.
 *
 * <p>Each service says once how to forget an island. Erasing one says it happened.
 */
public final class IslandCacheEviction {

    private static final Logger LOGGER = Logger.getLogger(IslandCacheEviction.class.getName());

    private final List<Consumer<IslandId>> forgetters = new CopyOnWriteArrayList<>();

    /** Registers one way to forget an island. Called once per service, while the server starts. */
    public void whenForgotten(Consumer<IslandId> forgetter) {
        forgetters.add(Objects.requireNonNull(forgetter, "forgetter must not be null"));
    }

    /**
     * Tells every service that this island is gone.
     *
     * <p>One service that throws must not stop the rest: the island is already erased, and the
     * others are holding memory for it either way.
     */
    public void forget(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        for (Consumer<IslandId> forgetter : forgetters) {
            try {
                forgetter.accept(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "One cache would not forget the island " + islandId);
            }
        }
    }

    /** How many services have said how to forget an island, for a test to assert they registered. */
    public int registered() {
        return forgetters.size();
    }
}
