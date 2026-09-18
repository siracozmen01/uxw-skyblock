package com.uxplima.uxmskyblock.core.application.event;

import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Outbound transport port for distributing domain events to external brokers / streams
 * (e.g., Redis Streams, Kafka) or cluster peers.
 */
public interface DurableEventTransportPort extends AutoCloseable {

    /**
     * Publishes an outbox event record to the target distributed stream.
     *
     * @param streamKey the stream/topic key (e.g. "uxmskyblock:stream:domain_events")
     * @param event the event record to publish
     */
    void publish(String streamKey, OutboxEventRecord event);

    /**
     * Subscribes a consumer group to the specified stream with at-least-once delivery.
     *
     * @param streamKey the stream/topic key
     * @param consumerGroup consumer group identifier
     * @param consumerName consumer worker name
     * @param handler callback invoked with the event and an acknowledge hook (e.g. XACK)
     * @return an AutoCloseable subscription handle
     */
    AutoCloseable subscribe(String streamKey, String consumerGroup, String consumerName, StreamEventHandler handler);

    @Override
    default void close() {}
}
