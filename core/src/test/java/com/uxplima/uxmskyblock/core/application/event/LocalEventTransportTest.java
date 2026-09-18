package com.uxplima.uxmskyblock.core.application.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalEventTransportTest {

    private LocalEventTransport transport;

    @BeforeEach
    void setUp() {
        transport = new LocalEventTransport();
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    private OutboxEventRecord createEvent(EventId id, String type) {
        return new OutboxEventRecord(
                id, type, "isl-1", "{}", OutboxStatus.PENDING, null, null, null, 0, null, null, Instant.now(), null);
    }

    @Test
    @DisplayName("Publishing an event records history and delivers to subscribed consumer groups")
    void publishAndDeliverToConsumerGroups() {
        String streamKey = "uxmskyblock:stream:domain_events";
        List<EventId> groupAEvents = new ArrayList<>();
        List<EventId> groupBEvents = new ArrayList<>();

        transport.subscribe(streamKey, "group-a", "worker-1", (event, ack) -> {
            groupAEvents.add(event.eventId());
            ack.run();
        });

        transport.subscribe(streamKey, "group-b", "worker-2", (event, ack) -> {
            groupBEvents.add(event.eventId());
            ack.run();
        });

        EventId e1 = EventId.random();
        OutboxEventRecord event1 = createEvent(e1, "ISLAND_CREATED");
        transport.publish(streamKey, event1);

        assertThat(transport.getStreamHistory(streamKey)).containsExactly(event1);
        assertThat(groupAEvents).containsExactly(e1);
        assertThat(groupBEvents).containsExactly(e1);
        assertThat(transport.isAcknowledged(e1)).isTrue();
    }

    @Test
    @DisplayName("Unsubscribe removes consumer from receiving further events")
    void unsubscribeStopsDelivery() throws Exception {
        String streamKey = "uxmskyblock:stream:test";
        List<EventId> received = new ArrayList<>();

        AutoCloseable sub = transport.subscribe(streamKey, "group-x", "worker-1", (event, ack) -> {
            received.add(event.eventId());
            ack.run();
        });

        EventId e1 = EventId.random();
        transport.publish(streamKey, createEvent(e1, "EVENT_1"));
        assertThat(received).containsExactly(e1);

        sub.close();

        EventId e2 = EventId.random();
        transport.publish(streamKey, createEvent(e2, "EVENT_2"));
        assertThat(received).containsExactly(e1); // e2 not received
        assertThat(transport.getStreamHistory(streamKey)).hasSize(2);
    }

    @Test
    @DisplayName("Publishing to closed transport throws IllegalStateException")
    void closedTransportThrows() {
        transport.close();
        assertThatThrownBy(() -> transport.publish("stream", createEvent(EventId.random(), "TEST")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }
}
