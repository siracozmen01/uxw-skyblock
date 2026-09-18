package com.uxplima.uxmskyblock.core.application.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * In-engine thread-safe implementation of {@link DurableEventTransportPort} supporting
 * named streams, consumer groups, and acknowledgment tracking for single-node and test environments.
 */
public final class LocalEventTransport implements DurableEventTransportPort {

    private static final Logger LOGGER = Logger.getLogger(LocalEventTransport.class.getName());

    private record Subscription(String consumerGroup, String consumerName, StreamEventHandler handler) {}

    private final Map<String, List<OutboxEventRecord>> streamLogs = new ConcurrentHashMap<>();
    private final Map<String, List<Subscription>> streamSubscriptions = new ConcurrentHashMap<>();
    private final Set<EventId> acknowledgedEvents = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Override
    public void publish(String streamKey, OutboxEventRecord event) {
        Objects.requireNonNull(streamKey, "streamKey must not be null");
        Objects.requireNonNull(event, "event must not be null");

        if (closed.get()) {
            throw new IllegalStateException("LocalEventTransport is closed");
        }

        streamLogs.computeIfAbsent(streamKey, k -> new CopyOnWriteArrayList<>()).add(event);

        List<Subscription> subs = streamSubscriptions.get(streamKey);
        if (subs == null || subs.isEmpty()) {
            return;
        }

        // Group subscriptions by consumer group: each group receives the message once
        Map<String, List<Subscription>> byGroup = new java.util.HashMap<>();
        for (Subscription sub : subs) {
            byGroup.computeIfAbsent(sub.consumerGroup(), k -> new ArrayList<>()).add(sub);
        }

        for (Map.Entry<String, List<Subscription>> entry : byGroup.entrySet()) {
            List<Subscription> groupMembers = entry.getValue();
            if (groupMembers.isEmpty()) {
                continue;
            }
            // Deliver to first member of the consumer group (or round-robin)
            Subscription target = groupMembers.getFirst();
            try {
                target.handler().onEvent(event, () -> acknowledgedEvents.add(event.eventId()));
            } catch (Exception e) {
                LOGGER.log(
                        Level.WARNING,
                        "Error delivering event " + event.eventId() + " to consumer group " + entry.getKey(),
                        e);
            }
        }
    }

    @Override
    public AutoCloseable subscribe(
            String streamKey, String consumerGroup, String consumerName, StreamEventHandler handler) {
        Objects.requireNonNull(streamKey, "streamKey must not be null");
        Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        Objects.requireNonNull(consumerName, "consumerName must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        Subscription sub = new Subscription(consumerGroup, consumerName, handler);
        streamSubscriptions
                .computeIfAbsent(streamKey, k -> new CopyOnWriteArrayList<>())
                .add(sub);

        return () -> {
            List<Subscription> list = streamSubscriptions.get(streamKey);
            if (list != null) {
                list.remove(sub);
            }
        };
    }

    public List<OutboxEventRecord> getStreamHistory(String streamKey) {
        List<OutboxEventRecord> records = streamLogs.get(streamKey);
        return records != null ? Collections.unmodifiableList(records) : Collections.emptyList();
    }

    public boolean isAcknowledged(EventId eventId) {
        return acknowledgedEvents.contains(eventId);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            streamSubscriptions.clear();
        }
    }
}
