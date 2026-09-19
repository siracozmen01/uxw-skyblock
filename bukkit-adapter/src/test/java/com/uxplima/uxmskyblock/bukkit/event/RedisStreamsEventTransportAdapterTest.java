package com.uxplima.uxmskyblock.bukkit.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmlib.redis.RedisStreamBus;
import com.uxplima.uxmskyblock.core.application.event.StreamEventHandler;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisStreamsEventTransportAdapterTest {

    private RedisStreamBus streamBus;
    private RedisStreamsEventTransportAdapter adapter;

    private final OutboxEventRecord sampleRecord = new OutboxEventRecord(
            EventId.of(UUID.randomUUID()),
            "IslandCreatedEvent",
            "aggregate-123",
            "{\"islandId\":\"123\"}",
            OutboxStatus.PENDING,
            null,
            null,
            null,
            0,
            null,
            null,
            Instant.now(),
            null);

    @BeforeEach
    void setUp() {
        streamBus = mock(RedisStreamBus.class);
        adapter = new RedisStreamsEventTransportAdapter(streamBus);
    }

    @Test
    @DisplayName("publish dispatches xadd to stream bus")
    void publishCallsXadd() {
        adapter.publish("test:stream", sampleRecord);
        Map<String, String> expectedMap = RedisStreamsEventTransportAdapter.toMap(sampleRecord);
        verify(streamBus).xadd("test:stream", expectedMap);
    }

    @Test
    @DisplayName("successful event processing invokes xack")
    void successfulProcessingCallsXack() {
        RedisStreamBus.StreamEntry entry =
                new RedisStreamBus.StreamEntry("msg-1", RedisStreamsEventTransportAdapter.toMap(sampleRecord));

        AtomicBoolean handled = new AtomicBoolean(false);
        StreamEventHandler handler = (event, ack) -> {
            handled.set(true);
            ack.run();
        };

        adapter.processEntry("test:stream", "group-1", entry, handler);

        assertThat(handled.get()).isTrue();
        verify(streamBus).xack("test:stream", "group-1", "msg-1");
    }

    @Test
    @DisplayName("exception in handler suppresses xack for broker retry")
    void failedProcessingSuppressesXack() {
        RedisStreamBus.StreamEntry entry =
                new RedisStreamBus.StreamEntry("msg-1", RedisStreamsEventTransportAdapter.toMap(sampleRecord));

        StreamEventHandler handler = (event, ack) -> {
            throw new RuntimeException("Simulated processing error");
        };

        adapter.processEntry("test:stream", "group-1", entry, handler);

        verify(streamBus, never()).xack(any(), any(), any());
    }

    @Test
    @DisplayName("message exceeding max retries routes to dead letter stream and acks")
    void exceededRetriesRoutesToDeadLetter() {
        OutboxEventRecord highRetryRecord = new OutboxEventRecord(
                sampleRecord.eventId(),
                sampleRecord.eventType(),
                sampleRecord.aggregateId(),
                sampleRecord.payload(),
                sampleRecord.status(),
                null,
                null,
                null,
                6,
                null,
                null,
                sampleRecord.createdAt(),
                null);

        Map<String, String> body = RedisStreamsEventTransportAdapter.toMap(highRetryRecord);
        RedisStreamBus.StreamEntry entry = new RedisStreamBus.StreamEntry("msg-1", body);

        StreamEventHandler handler = mock(StreamEventHandler.class);

        adapter.processEntry("test:stream", "group-1", entry, handler);

        verify(streamBus).xadd(eq(RedisStreamsEventTransportAdapter.DEFAULT_DEAD_LETTER_STREAM), eq(body));
        verify(streamBus).xack("test:stream", "group-1", "msg-1");
    }

    @Test
    @DisplayName("round-trip serialization preserves all event fields")
    void roundTripSerializationPreservesFields() {
        OutboxEventRecord fullRecord = new OutboxEventRecord(
                EventId.of(UUID.randomUUID()),
                "IslandDeletedEvent",
                "agg-456",
                "{\"deleted\":true}",
                OutboxStatus.CLAIMED,
                "node-1",
                "tok-abc",
                Instant.ofEpochMilli(1700000000000L),
                3,
                Instant.ofEpochMilli(1700000050000L),
                "Transient timeout",
                Instant.ofEpochMilli(1699999000000L),
                Instant.ofEpochMilli(1700000090000L));

        Map<String, String> map = RedisStreamsEventTransportAdapter.toMap(fullRecord);
        OutboxEventRecord restored = RedisStreamsEventTransportAdapter.fromMap(map);

        assertThat(restored.eventId()).isEqualTo(fullRecord.eventId());
        assertThat(restored.eventType()).isEqualTo(fullRecord.eventType());
        assertThat(restored.aggregateId()).isEqualTo(fullRecord.aggregateId());
        assertThat(restored.payload()).isEqualTo(fullRecord.payload());
        assertThat(restored.status()).isEqualTo(fullRecord.status());
        assertThat(restored.claimOwner()).isEqualTo(fullRecord.claimOwner());
        assertThat(restored.claimToken()).isEqualTo(fullRecord.claimToken());
        assertThat(restored.claimExpiresAt()).isEqualTo(fullRecord.claimExpiresAt());
        assertThat(restored.retryCount()).isEqualTo(fullRecord.retryCount());
        assertThat(restored.nextAttemptAt()).isEqualTo(fullRecord.nextAttemptAt());
        assertThat(restored.lastError()).isEqualTo(fullRecord.lastError());
        assertThat(restored.createdAt()).isEqualTo(fullRecord.createdAt());
        assertThat(restored.processedAt()).isEqualTo(fullRecord.processedAt());
    }

    @Test
    @DisplayName("malformed entry safely acks to prevent head-of-line blocking")
    void malformedEntryAcksSafely() throws Exception {
        RedisStreamBus.StreamEntry corruptedEntry = new RedisStreamBus.StreamEntry("bad-msg", Map.of("foo", "bar"));

        StreamEventHandler handler = mock(StreamEventHandler.class);
        adapter.processEntry("test:stream", "group-1", corruptedEntry, handler);

        verify(streamBus).xack("test:stream", "group-1", "bad-msg");
        verify(handler, never()).onEvent(any(), any());
    }
}
