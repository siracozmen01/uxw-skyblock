package com.uxplima.uxmskyblock.core.application.event;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;

/**
 * Enterprise Redis Streams implementation of {@link DurableEventTransportPort}
 * adhering to Section 2.15:
 * <ul>
 *   <li>Publishes to domain event stream (default: "uxmskyblock:stream:domain_events") via XADD.</li>
 *   <li>Consumer group reads with at-least-once delivery (XREADGROUP).</li>
 *   <li>Dead-letter stream escalation ("uxmskyblock:stream:dead_letter") after max retries (5).</li>
 *   <li>Explicit acknowledgment (XACK) strictly after successful processing or novel inbox commit.</li>
 *   <li>Stale consumer auto-claim (XAUTOCLAIM) for pending messages exceeding 30 seconds.</li>
 * </ul>
 */
public final class RedisStreamsEventTransportAdapter implements DurableEventTransportPort {

    private static final Logger LOGGER = Logger.getLogger(RedisStreamsEventTransportAdapter.class.getName());

    public static final String DEFAULT_DOMAIN_STREAM = "uxmskyblock:stream:domain_events";
    public static final String DEFAULT_DEAD_LETTER_STREAM = "uxmskyblock:stream:dead_letter";
    public static final int DEFAULT_MAX_RETRIES = 5;
    public static final Duration DEFAULT_PENDING_TIMEOUT = Duration.ofSeconds(30);

    public interface StreamDriver {
        void xadd(String streamKey, OutboxEventRecord record);
        List<StreamMessage> xreadgroup(String streamKey, String group, String consumer, int count, Duration block);
        void xack(String streamKey, String group, String messageId);
        List<StreamMessage> xautoclaim(String streamKey, String group, String consumer, Duration minIdleTime, int count);
        default boolean isHealthy() {
            return true;
        }
    }

    public record StreamMessage(String messageId, OutboxEventRecord record, int deliveryCount, Instant firstDeliveredAt) {
        public StreamMessage {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(firstDeliveredAt, "firstDeliveredAt");
        }
    }

    private final StreamDriver driver;
    private final String deadLetterStreamKey;
    private final int maxRetries;
    private final Duration pendingTimeout;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public RedisStreamsEventTransportAdapter(
            StreamDriver driver,
            String deadLetterStreamKey,
            int maxRetries,
            Duration pendingTimeout) {
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
        this.deadLetterStreamKey = Objects.requireNonNull(deadLetterStreamKey, "deadLetterStreamKey must not be null");
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.pendingTimeout = Objects.requireNonNull(pendingTimeout, "pendingTimeout must not be null");
    }

    public RedisStreamsEventTransportAdapter(StreamDriver driver) {
        this(driver, DEFAULT_DEAD_LETTER_STREAM, DEFAULT_MAX_RETRIES, DEFAULT_PENDING_TIMEOUT);
    }

    @Override
    public void publish(String streamKey, OutboxEventRecord event) {
        Objects.requireNonNull(streamKey, "streamKey must not be null");
        Objects.requireNonNull(event, "event must not be null");

        if (!running.get()) {
            throw new IllegalStateException("RedisStreamsEventTransportAdapter is closed");
        }

        driver.xadd(streamKey, event);
    }

    @Override
    public AutoCloseable subscribe(
            String streamKey, String consumerGroup, String consumerName, StreamEventHandler handler) {
        Objects.requireNonNull(streamKey, "streamKey must not be null");
        Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        Objects.requireNonNull(consumerName, "consumerName must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        AtomicBoolean active = new AtomicBoolean(true);
        Thread workerThread = new Thread(() -> {
            while (active.get() && running.get()) {
                try {
                    // 1. Check for stale messages via XAUTOCLAIM
                    List<StreamMessage> claimed = driver.xautoclaim(streamKey, consumerGroup, consumerName, pendingTimeout, 10);
                    for (StreamMessage msg : claimed) {
                        processMessage(streamKey, consumerGroup, msg, handler);
                    }

                    // 2. Read new messages via XREADGROUP
                    List<StreamMessage> messages = driver.xreadgroup(streamKey, consumerGroup, consumerName, 50, Duration.ofSeconds(1));
                    for (StreamMessage msg : messages) {
                        processMessage(streamKey, consumerGroup, msg, handler);
                    }
                } catch (Exception e) {
                    if (active.get() && running.get()) {
                        LOGGER.log(Level.WARNING, "Error in stream poll loop for " + streamKey + " / " + consumerGroup, e);
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
        }, "RedisStreamsConsumer-" + consumerGroup + "-" + consumerName);

        workerThread.setDaemon(true);
        workerThread.start();

        return () -> {
            active.set(false);
            workerThread.interrupt();
        };
    }

    public void processMessage(
            String streamKey, String consumerGroup, StreamMessage msg, StreamEventHandler handler) {
        if (msg.deliveryCount() > maxRetries) {
            LOGGER.warning(() -> "Event " + msg.record().eventId() + " exceeded max retries ("
                    + msg.deliveryCount() + " > " + maxRetries + "). Routing to dead-letter stream " + deadLetterStreamKey);
            try {
                driver.xadd(deadLetterStreamKey, msg.record());
                driver.xack(streamKey, consumerGroup, msg.messageId());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to route message " + msg.messageId() + " to dead letter stream", e);
            }
            return;
        }

        try {
            handler.onEvent(msg.record(), () -> driver.xack(streamKey, consumerGroup, msg.messageId()));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Consumer failed to process message " + msg.messageId() + ", skipping XACK for retry", e);
        }
    }

    public boolean isHealthy() {
        return running.get() && driver.isHealthy();
    }

    @Override
    public void close() {
        running.set(false);
    }
}
