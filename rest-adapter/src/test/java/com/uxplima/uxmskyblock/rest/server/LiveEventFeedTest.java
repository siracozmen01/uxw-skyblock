package com.uxplima.uxmskyblock.rest.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiveEventFeedTest {

    private static OutboxEventRecord event(String type, String payload) {
        return new OutboxEventRecord(
                EventId.of(UUID.randomUUID()),
                type,
                UUID.randomUUID().toString(),
                payload,
                OutboxStatus.PENDING,
                null,
                null,
                null,
                0,
                null,
                null,
                Instant.parse("2026-09-21T12:00:00Z"),
                null);
    }

    @Test
    @DisplayName("A JSON payload reaches the viewer as an object, not as a quoted string")
    void jsonPayloadIsNested() {
        OutboxEventRecord record = event("island.created", "{\"islandId\":\"abc\",\"level\":3}");

        JsonObject frame =
                JsonParser.parseString(LiveEventFeed.frameFor(record)).getAsJsonObject();

        assertThat(frame.get("type").getAsString()).isEqualTo("island.created");
        assertThat(frame.get("aggregateId").getAsString()).isEqualTo(record.aggregateId());
        assertThat(frame.get("occurredAt").getAsString()).isEqualTo("2026-09-21T12:00:00Z");
        assertThat(frame.getAsJsonObject("data").get("islandId").getAsString()).isEqualTo("abc");
        assertThat(frame.getAsJsonObject("data").get("level").getAsInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("A payload that is not JSON is passed through rather than dropped")
    void nonJsonPayloadSurvives() {
        OutboxEventRecord record = event("island.renamed", "not json at all");

        JsonObject frame =
                JsonParser.parseString(LiveEventFeed.frameFor(record)).getAsJsonObject();

        assertThat(frame.get("data").getAsString()).isEqualTo("not json at all");
    }

    @Test
    @DisplayName("An event with nobody watching is not an error")
    void consumingWithNoViewersIsQuiet() {
        LiveEventFeed feed = new LiveEventFeed();

        assertThatCode(() -> feed.consume(event("island.created", "{}"))).doesNotThrowAnyException();
        assertThat(feed.viewerCount()).isZero();
    }

    @Test
    @DisplayName("Consuming never throws, so a closed browser tab cannot make the outbox retry")
    void consumeNeverThrows() throws Exception {
        LiveEventFeed feed = new LiveEventFeed();

        // The dispatcher treats a thrown consumer as a failed delivery and retries with backoff.
        // A live overlay is best effort, so this method must swallow everything it meets.
        feed.consume(event("island.created", "{\"broken\":"));

        assertThat(feed.viewerCount()).isZero();
    }
}
