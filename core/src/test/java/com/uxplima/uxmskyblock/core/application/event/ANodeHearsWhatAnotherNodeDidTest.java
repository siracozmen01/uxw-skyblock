package com.uxplima.uxmskyblock.core.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A node forgets what it remembers about an island another node changed.
 *
 * <p>Every node published its domain events onto the stream and no node ever subscribed to it. The
 * transport was written, a Redis Streams adapter implemented it with consumer groups and stale
 * message reclaim, a deduplicating handler was written for exactly this shape of delivery, and the
 * whole fabric was write only: a node that froze an island, archived one or handed one to a new
 * owner told every other node, and every other node went on answering out of what it remembered.
 */
class ANodeHearsWhatAnotherNodeDidTest {

    private static OutboxEventRecord eventOf(String type, String aggregateId) {
        return new OutboxEventRecord(
                EventId.random(),
                type,
                aggregateId,
                "{}",
                OutboxStatus.PENDING,
                null,
                null,
                null,
                0,
                null,
                null,
                Instant.now(),
                null);
    }

    @Test
    @DisplayName("An island another node froze is forgotten here")
    void anislandFrozenElsewhereIsForgotten() throws Exception {
        List<IslandId> forgotten = new ArrayList<>();
        IslandId islandId = IslandId.of(UUID.randomUUID());

        new ClusterIslandCacheInvalidation(forgotten::add)
                .consume(eventOf("ISLAND_FROZEN", islandId.value().toString()));

        assertThat(forgotten).containsExactly(islandId);
    }

    @Test
    @DisplayName("Every event that moves what a node holds in memory is one of them")
    void everystaleMakingEventCounts() throws Exception {
        for (String type : ClusterIslandCacheInvalidation.STALE_MAKING_EVENTS) {
            List<IslandId> forgotten = new ArrayList<>();
            IslandId islandId = IslandId.of(UUID.randomUUID());

            new ClusterIslandCacheInvalidation(forgotten::add)
                    .consume(eventOf(type, islandId.value().toString()));

            assertThat(forgotten).describedAs("%s", type).containsExactly(islandId);
        }
    }

    @Test
    @DisplayName("A bank movement changes nothing here, so nothing is thrown away for it")
    void abankMovementCostsNothing() throws Exception {
        List<IslandId> forgotten = new ArrayList<>();

        new ClusterIslandCacheInvalidation(forgotten::add)
                .consume(eventOf("ISLAND_BANK_TRANSACTION", UUID.randomUUID().toString()));

        assertThat(forgotten)
                .describedAs("an island's bank is read from the database every time it is asked for")
                .isEmpty();
    }

    @Test
    @DisplayName("An event about something that is not an island is somebody else's event")
    void anonIslandAggregateIsLeftAlone() throws Exception {
        List<IslandId> forgotten = new ArrayList<>();

        new ClusterIslandCacheInvalidation(forgotten::add).consume(eventOf("ISLAND_FROZEN", "not-a-uuid"));

        assertThat(forgotten).isEmpty();
    }

    @Test
    @DisplayName("The same event delivered twice is acted on once")
    void thesameEventTwiceIsActedOnOnce() throws Exception {
        List<IslandId> forgotten = new ArrayList<>();
        IslandId islandId = IslandId.of(UUID.randomUUID());
        OutboxEventRecord event = eventOf("ISLAND_ARCHIVED", islandId.value().toString());

        ConsumerInboxPort inbox = new InMemoryInbox();
        InboxDeduplicatingConsumer handler =
                new InboxDeduplicatingConsumer("node-1", inbox, new ClusterIslandCacheInvalidation(forgotten::add));

        handler.onEvent(event, () -> {});
        handler.onEvent(event, () -> {});

        assertThat(forgotten)
                .describedAs("the transport reclaims a stale message and delivers it again")
                .containsExactly(islandId);
    }

    /** Remembers what it has been told, which is all a dedupe needs. */
    private static final class InMemoryInbox implements ConsumerInboxPort {
        private final java.util.Set<String> marks = java.util.concurrent.ConcurrentHashMap.newKeySet();

        @Override
        public boolean markProcessedIfAbsent(String consumerName, EventId eventId) {
            return marks.add(consumerName + ":" + eventId.value());
        }

        @Override
        public boolean isProcessed(String consumerName, EventId eventId) {
            return marks.contains(consumerName + ":" + eventId.value());
        }

        @Override
        public void forget(String consumerName, EventId eventId) {
            marks.remove(consumerName + ":" + eventId.value());
        }

        @Override
        public int purgeProcessedBefore(Instant before) {
            return 0;
        }
    }
}
