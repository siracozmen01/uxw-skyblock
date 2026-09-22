package com.uxplima.uxmskyblock.core.application.event;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Forgets what this node remembers about an island another node changed.
 *
 * <p>Every node publishes its domain events onto the stream and no node ever subscribed to it. The
 * transport was written, a Redis Streams adapter implemented it with consumer groups and stale
 * message reclaim, a deduplicating handler was written for exactly this shape of delivery, and the
 * whole fabric was write only. A node that froze an island, archived one or handed one to a new
 * owner told every other node, and every other node went on answering out of what it remembered.
 *
 * <p>Only the events that change what a node holds in memory count. An island's bank is read from
 * the database every time it is asked for, so a deposit on another node changes nothing here and
 * throwing away an island's caches on every deposit would be a cost for nothing.
 *
 * <p>A node hears its own events back. Forgetting an island it has just changed itself costs one
 * re-read and these events are rare, so nothing here tries to tell the two apart.
 */
public final class ClusterIslandCacheInvalidation implements OutboxEventConsumer {

    /**
     * What makes a node's memory of an island out of date.
     *
     * <p>These are the events that move an island's members, its flags, its edge, its name or its
     * lifecycle. A bank movement is not one of them and neither is a profile switch.
     */
    public static final Set<String> STALE_MAKING_EVENTS = Set.of(
            "ISLAND_CREATED",
            "ISLAND_RECYCLED",
            "ISLAND_ARCHIVED",
            "ISLAND_FROZEN",
            "ISLAND_UNFROZEN",
            "ISLAND_RENAMED",
            "ISLAND_NAME_RESET",
            "ISLAND_LEADER_SUCCESSION",
            "ISLAND_ECONOMIC_STATE_CHANGED");

    private final Consumer<IslandId> forget;

    public ClusterIslandCacheInvalidation(Consumer<IslandId> forget) {
        this.forget = Objects.requireNonNull(forget, "forget must not be null");
    }

    @Override
    public void consume(OutboxEventRecord event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!STALE_MAKING_EVENTS.contains(event.eventType())) {
            return;
        }
        islandOf(event.aggregateId()).ifPresent(forget);
    }

    /**
     * The island an event is about, when its aggregate really is one.
     *
     * <p>The stream carries every domain event this plugin stages and not all of them are about an
     * island. An aggregate id that is not an island id is not an error here, it is somebody else's
     * event.
     */
    private static java.util.Optional<IslandId> islandOf(String aggregateId) {
        try {
            return java.util.Optional.of(IslandId.of(UUID.fromString(aggregateId)));
        } catch (IllegalArgumentException notAnIslandId) {
            return java.util.Optional.empty();
        }
    }
}
