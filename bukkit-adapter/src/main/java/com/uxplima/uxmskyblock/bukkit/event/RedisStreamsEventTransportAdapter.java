package com.uxplima.uxmskyblock.bukkit.event;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmlib.redis.RedisStreamBus;
import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.application.event.StreamEventHandler;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;

/**
 * Enterprise Redis Streams implementation of {@link DurableEventTransportPort}
 * located in the infrastructure/adapter layer, adhering to Section 2.15 & Section 11:
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

    private final RedisStreamBus streamBus;
    private final String deadLetterStreamKey;
    private final int maxRetries;
    private final Duration pendingTimeout;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentMap<String, Integer> deliveryAttempts = new ConcurrentHashMap<>();

    public RedisStreamsEventTransportAdapter(
            RedisStreamBus streamBus, String deadLetterStreamKey, int maxRetries, Duration pendingTimeout) {
        this.streamBus = Objects.requireNonNull(streamBus, "streamBus must not be null");
        this.deadLetterStreamKey = Objects.requireNonNull(deadLetterStreamKey, "deadLetterStreamKey must not be null");
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.pendingTimeout = Objects.requireNonNull(pendingTimeout, "pendingTimeout must not be null");
    }

    public RedisStreamsEventTransportAdapter(RedisStreamBus streamBus) {
        this(streamBus, DEFAULT_DEAD_LETTER_STREAM, DEFAULT_MAX_RETRIES, DEFAULT_PENDING_TIMEOUT);
    }

    @Override
    public void publish(String streamKey, OutboxEventRecord event) {
        Objects.requireNonNull(streamKey, "streamKey must not be null");
        Objects.requireNonNull(event, "event must not be null");

        if (!running.get()) {
            throw new IllegalStateException("RedisStreamsEventTransportAdapter is closed");
        }

        streamBus.xadd(streamKey, toMap(event));
    }

    @Override
    public AutoCloseable subscribe(
            String streamKey, String consumerGroup, String consumerName, StreamEventHandler handler) {
        Objects.requireNonNull(streamKey, "streamKey must not be null");
        Objects.requireNonNull(consumerGroup, "consumerGroup must not be null");
        Objects.requireNonNull(consumerName, "consumerName must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        streamBus.createGroupIfNotExists(streamKey, consumerGroup);

        AtomicBoolean active = new AtomicBoolean(true);
        Thread workerThread = new Thread(
                () -> {
                    while (active.get() && running.get()) {
                        try {
                            // 1. Check for stale messages via XAUTOCLAIM
                            List<RedisStreamBus.StreamEntry> claimed =
                                    streamBus.xautoclaim(streamKey, consumerGroup, consumerName, pendingTimeout, 10);
                            for (RedisStreamBus.StreamEntry entry : claimed) {
                                processEntry(streamKey, consumerGroup, entry, handler);
                            }

                            // 2. Read new messages via XREADGROUP
                            List<RedisStreamBus.StreamEntry> messages = streamBus.xreadgroup(
                                    streamKey, consumerGroup, consumerName, 50, Duration.ofSeconds(1));
                            for (RedisStreamBus.StreamEntry entry : messages) {
                                processEntry(streamKey, consumerGroup, entry, handler);
                            }
                        } catch (Exception e) {
                            if (active.get() && running.get()) {
                                LOGGER.log(
                                        Level.WARNING,
                                        "Error in stream poll loop for " + streamKey + " / " + consumerGroup,
                                        e);
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    }
                },
                "RedisStreamsConsumer-" + consumerGroup + "-" + consumerName);

        workerThread.setDaemon(true);
        workerThread.start();

        return () -> {
            active.set(false);
            workerThread.interrupt();
        };
    }

    public void processEntry(
            String streamKey, String consumerGroup, RedisStreamBus.StreamEntry entry, StreamEventHandler handler) {
        String entryKey = consumerGroup + ":" + entry.id();
        OutboxEventRecord record;
        try {
            record = fromMap(entry.body());
        } catch (Exception e) {
            LOGGER.log(
                    Level.SEVERE,
                    "Failed to deserialize event from stream entry " + entry.id() + ", routing to dead-letter stream "
                            + deadLetterStreamKey,
                    e);
            try {
                streamBus.xadd(deadLetterStreamKey, entry.body());
                streamBus.xack(streamKey, consumerGroup, entry.id());
                deliveryAttempts.remove(entryKey);
            } catch (Exception dlqError) {
                LOGGER.log(
                        Level.SEVERE,
                        "Failed to route malformed stream entry " + entry.id() + " to dead letter stream",
                        dlqError);
                // NEVER XACK IF DLQ XADD THROWS
            }
            return;
        }

        int attempts = deliveryAttempts.compute(entryKey, (k, v) -> v == null ? 1 : v + 1);
        if (attempts > maxRetries) {
            LOGGER.warning(() -> "Stream entry " + entry.id() + " exceeded max retries (" + attempts + " > "
                    + maxRetries + "). Routing to dead-letter stream " + deadLetterStreamKey);
            try {
                streamBus.xadd(deadLetterStreamKey, entry.body());
                streamBus.xack(streamKey, consumerGroup, entry.id());
                deliveryAttempts.remove(entryKey);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to route message " + entry.id() + " to dead letter stream", e);
                // NEVER XACK IF DLQ XADD THROWS
            }
            return;
        }

        try {
            handler.onEvent(record, () -> {
                streamBus.xack(streamKey, consumerGroup, entry.id());
                deliveryAttempts.remove(entryKey);
            });
        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING, "Consumer failed to process message " + entry.id() + ", skipping XACK for retry", e);
        }
    }

    public boolean isHealthy() {
        return running.get() && streamBus.healthy();
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            streamBus.close();
        }
    }

    public static Map<String, String> toMap(OutboxEventRecord record) {
        Map<String, String> map = new HashMap<>();
        map.put("eventId", record.eventId().value().toString());
        map.put("eventType", record.eventType());
        map.put("aggregateId", record.aggregateId());
        map.put("payload", record.payload());
        map.put("status", record.status().name());
        if (record.claimOwner() != null) {
            map.put("claimOwner", record.claimOwner());
        }
        if (record.claimToken() != null) {
            map.put("claimToken", record.claimToken());
        }
        if (record.claimExpiresAt() != null) {
            map.put("claimExpiresAt", record.claimExpiresAt().toString());
        }
        map.put("retryCount", String.valueOf(record.retryCount()));
        if (record.nextAttemptAt() != null) {
            map.put("nextAttemptAt", record.nextAttemptAt().toString());
        }
        if (record.lastError() != null) {
            map.put("lastError", record.lastError());
        }
        map.put("createdAt", record.createdAt().toString());
        if (record.processedAt() != null) {
            map.put("processedAt", record.processedAt().toString());
        }
        return map;
    }

    public static OutboxEventRecord fromMap(Map<String, String> map) {
        String rawEventId = map.get("eventId");
        if (rawEventId == null) {
            throw new IllegalArgumentException("Missing required eventId in stream event payload");
        }
        EventId eventId = EventId.fromString(rawEventId);
        String eventType = map.getOrDefault("eventType", "unknown");
        String aggregateId = map.getOrDefault("aggregateId", "global");
        String payload = map.getOrDefault("payload", "{}");
        OutboxStatus status = OutboxStatus.valueOf(map.getOrDefault("status", "PENDING"));
        String claimOwner = map.get("claimOwner");
        String claimToken = map.get("claimToken");
        Instant claimExpiresAt = map.get("claimExpiresAt") != null ? Instant.parse(map.get("claimExpiresAt")) : null;
        int retryCount = 0;
        try {
            retryCount = Integer.parseInt(map.getOrDefault("retryCount", "0"));
        } catch (NumberFormatException ignored) {
            // Default to retryCount = 0 on parse failure
        }
        Instant nextAttemptAt = map.get("nextAttemptAt") != null ? Instant.parse(map.get("nextAttemptAt")) : null;
        String lastError = map.get("lastError");
        Instant createdAt = map.get("createdAt") != null ? Instant.parse(map.get("createdAt")) : Instant.now();
        Instant processedAt = map.get("processedAt") != null ? Instant.parse(map.get("processedAt")) : null;
        return new OutboxEventRecord(
                eventId,
                eventType,
                aggregateId,
                payload,
                status,
                claimOwner,
                claimToken,
                claimExpiresAt,
                retryCount,
                nextAttemptAt,
                lastError,
                createdAt,
                processedAt);
    }
}
