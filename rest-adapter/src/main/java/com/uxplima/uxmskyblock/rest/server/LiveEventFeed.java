package com.uxplima.uxmskyblock.rest.server;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.event.OutboxEventConsumer;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import io.javalin.websocket.WsContext;

/**
 * The live event feed behind {@code WS /api/v1/events}.
 *
 * <p>It is an {@link OutboxEventConsumer}, so the frames a map overlay receives are exactly the
 * events the plugin durably staged, in the order the dispatcher hands them over. Nothing is
 * invented for the socket and nothing reaches the socket that did not reach the database first.
 *
 * <p><b>A failed send is never reported back to the dispatcher.</b> The dispatcher treats a thrown
 * consumer as a delivery failure and retries the event with backoff. A browser that closed its tab
 * must not make an island creation redeliver five times. Durable delivery is the outbox's promise;
 * this feed is best effort by design, and says so in the log when it drops a frame.
 */
public final class LiveEventFeed implements OutboxEventConsumer {

    private static final Logger LOGGER = Logger.getLogger(LiveEventFeed.class.getName());

    /** A viewer who cannot keep up is disconnected rather than allowed to grow a queue in memory. */
    private static final int MAX_VIEWERS = 64;

    private final Set<WsContext> viewers = ConcurrentHashMap.newKeySet();

    /** Registers a connected viewer, refusing one more than the server will hold. */
    public boolean register(WsContext viewer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        if (viewers.size() >= MAX_VIEWERS) {
            return false;
        }
        viewers.add(viewer);
        return true;
    }

    public void unregister(WsContext viewer) {
        viewers.remove(viewer);
    }

    public int viewerCount() {
        return viewers.size();
    }

    /** Closes every open socket, so a plugin shutdown does not leave a viewer hanging. */
    public void closeAll() {
        for (WsContext viewer : viewers) {
            try {
                viewer.closeSession(1001, "The server is shutting down.");
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "A viewer socket refused to close cleanly", e);
            }
        }
        viewers.clear();
    }

    @Override
    public void consume(OutboxEventRecord event) {
        Objects.requireNonNull(event, "event must not be null");
        if (viewers.isEmpty()) {
            return;
        }
        String frame = frameFor(event);
        for (WsContext viewer : viewers) {
            send(viewer, frame);
        }
    }

    /** Sends one frame to one viewer, and drops the viewer rather than the event when it fails. */
    public void send(WsContext viewer, String frame) {
        try {
            if (viewer.session.isOpen()) {
                viewer.send(frame);
            } else {
                viewers.remove(viewer);
            }
        } catch (Exception e) {
            viewers.remove(viewer);
            LOGGER.log(Level.FINE, "Dropped a viewer that would not take a frame", e);
        }
    }

    /**
     * Renders one event as the frame a viewer reads.
     *
     * <p>The payload is already JSON in the outbox, so it is parsed rather than quoted: a viewer
     * should read {@code data.islandId}, not a string it has to parse a second time. A payload that
     * is not JSON is passed through as text rather than dropped, because an event a viewer cannot
     * read is still better than an event nobody knows happened.
     */
    static String frameFor(OutboxEventRecord event) {
        JsonObject frame = new JsonObject();
        frame.addProperty("eventId", event.eventId().value().toString());
        frame.addProperty("type", event.eventType());
        frame.addProperty("aggregateId", event.aggregateId());
        frame.addProperty("occurredAt", event.createdAt().toString());
        try {
            frame.add("data", JsonParser.parseString(event.payload()));
        } catch (RuntimeException notJson) {
            frame.addProperty("data", event.payload());
        }
        return frame.toString();
    }

    /** The frame a viewer receives the moment it connects, so it knows the feed is live. */
    static String helloFrame(String nodeId) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "feed.connected");
        frame.addProperty("nodeId", nodeId);
        frame.addProperty("occurredAt", Instant.now().toString());
        return frame.toString();
    }
}
