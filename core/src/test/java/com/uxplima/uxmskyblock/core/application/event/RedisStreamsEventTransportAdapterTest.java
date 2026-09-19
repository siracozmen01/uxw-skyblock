package com.uxplima.uxmskyblock.core.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisStreamsEventTransportAdapterTest {

    private RedisStreamsEventTransportAdapter.StreamDriver driver;
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
        driver = mock(RedisStreamsEventTransportAdapter.StreamDriver.class);
        adapter = new RedisStreamsEventTransportAdapter(driver);
    }

    @Test
    @DisplayName("publish dispatches xadd to stream driver")
    void publishCallsXadd() {
        adapter.publish("test:stream", sampleRecord);
        verify(driver).xadd("test:stream", sampleRecord);
    }

    @Test
    @DisplayName("successful event processing invokes xack")
    void successfulProcessingCallsXack() {
        RedisStreamsEventTransportAdapter.StreamMessage msg =
                new RedisStreamsEventTransportAdapter.StreamMessage("msg-1", sampleRecord, 1, Instant.now());

        AtomicBoolean handled = new AtomicBoolean(false);
        StreamEventHandler handler = (event, ack) -> {
            handled.set(true);
            ack.run();
        };

        adapter.processMessage("test:stream", "group-1", msg, handler);

        assertThat(handled.get()).isTrue();
        verify(driver).xack("test:stream", "group-1", "msg-1");
    }

    @Test
    @DisplayName("exception in handler suppresses xack for broker retry")
    void failedProcessingSuppressesXack() {
        RedisStreamsEventTransportAdapter.StreamMessage msg =
                new RedisStreamsEventTransportAdapter.StreamMessage("msg-1", sampleRecord, 1, Instant.now());

        StreamEventHandler handler = (event, ack) -> {
            throw new RuntimeException("Simulated processing error");
        };

        adapter.processMessage("test:stream", "group-1", msg, handler);

        verify(driver, never()).xack(any(), any(), any());
    }

    @Test
    @DisplayName("message exceeding max retries routes to dead letter stream and acks")
    void exceededRetriesRoutesToDeadLetter() {
        RedisStreamsEventTransportAdapter.StreamMessage msg =
                new RedisStreamsEventTransportAdapter.StreamMessage("msg-1", sampleRecord, 6, Instant.now());

        StreamEventHandler handler = mock(StreamEventHandler.class);

        adapter.processMessage("test:stream", "group-1", msg, handler);

        verify(driver).xadd(eq(RedisStreamsEventTransportAdapter.DEFAULT_DEAD_LETTER_STREAM), eq(sampleRecord));
        verify(driver).xack("test:stream", "group-1", "msg-1");
    }
}
