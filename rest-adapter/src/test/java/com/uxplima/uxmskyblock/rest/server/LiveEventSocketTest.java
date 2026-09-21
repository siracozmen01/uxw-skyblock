package com.uxplima.uxmskyblock.rest.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.OutboxStatus;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.rest.config.RestConfiguration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The documented {@code WS /api/v1/events} endpoint, opened for real.
 *
 * <p>The enterprise foundation document has published this endpoint since the beginning and the
 * code answered nothing at that path. A map overlay written against the document connected and was
 * refused with a 404 upgrade.
 */
class LiveEventSocketTest {

    private static final String TOKEN = "a-token-an-operator-chose";
    private static final ServerNodeId NODE_ID = ServerNodeId.of("test-node");

    private RestServer restServer;
    private String wsUrl;

    /** Collects the frames one viewer receives, so a test can wait for the one it wants. */
    private static final class Viewer implements WebSocket.Listener {
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private volatile int closeCode = -1;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frames.add(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            closed.complete(null);
            return CompletableFuture.completedFuture(null);
        }

        String awaitFrame(int index) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (frames.size() <= index && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            Assertions.assertThat(frames).describedAs("frames received").hasSizeGreaterThan(index);
            return frames.get(index);
        }
    }

    @BeforeEach
    void setUp() {
        RestConfiguration config = new RestConfiguration(true, "127.0.0.1", 0, TOKEN);
        restServer = new RestServer(
                config,
                NODE_ID,
                mock(IslandStoragePort.class),
                mock(IslandBankService.class),
                mock(IslandLeaderboardService.class),
                null);
        restServer.start();
        wsUrl = "ws://127.0.0.1:" + restServer.port() + "/api/v1/events";
    }

    @AfterEach
    void tearDown() {
        if (restServer != null) {
            restServer.close();
        }
    }

    private WebSocket connect(Viewer viewer, String query) throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(wsUrl + query), viewer)
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("A viewer with the token connects and is told the feed is live")
    void connectsWithTheToken() throws Exception {
        Viewer viewer = new Viewer();

        WebSocket socket = connect(viewer, "?token=" + TOKEN);

        JsonObject hello = JsonParser.parseString(viewer.awaitFrame(0)).getAsJsonObject();
        assertThat(hello.get("type").getAsString()).isEqualTo("feed.connected");
        assertThat(hello.get("nodeId").getAsString()).isEqualTo("test-node");
        assertThat(restServer.liveEventViewers()).isEqualTo(1);
        socket.abort();
    }

    @Test
    @DisplayName("A viewer without the token is closed, not served")
    void refusesAViewerWithoutTheToken() throws Exception {
        Viewer viewer = new Viewer();

        connect(viewer, "?token=not-the-token");

        viewer.closed.get(5, TimeUnit.SECONDS);
        assertThat(viewer.closeCode).isEqualTo(1008);
        assertThat(viewer.frames).isEmpty();
        assertThat(restServer.liveEventViewers()).isZero();
    }

    @Test
    @DisplayName("An event the plugin staged reaches every connected viewer")
    void anEventReachesTheViewers() throws Exception {
        Viewer first = new Viewer();
        Viewer second = new Viewer();
        WebSocket firstSocket = connect(first, "?token=" + TOKEN);
        WebSocket secondSocket = connect(second, "?token=" + TOKEN);
        first.awaitFrame(0);
        second.awaitFrame(0);

        restServer
                .liveEventFeed()
                .consume(new OutboxEventRecord(
                        EventId.of(UUID.randomUUID()),
                        "island.created",
                        "island-1",
                        "{\"islandId\":\"island-1\"}",
                        OutboxStatus.PENDING,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        Instant.now(),
                        null));

        for (Viewer viewer : List.of(first, second)) {
            JsonObject frame = JsonParser.parseString(viewer.awaitFrame(1)).getAsJsonObject();
            assertThat(frame.get("type").getAsString()).isEqualTo("island.created");
            assertThat(frame.getAsJsonObject("data").get("islandId").getAsString())
                    .isEqualTo("island-1");
        }
        firstSocket.abort();
        secondSocket.abort();
    }

    @Test
    @DisplayName("Closing the server closes the sockets it opened")
    void closingTheServerClosesTheSockets() throws Exception {
        Viewer viewer = new Viewer();
        connect(viewer, "?token=" + TOKEN);
        viewer.awaitFrame(0);

        restServer.close();

        viewer.closed.get(5, TimeUnit.SECONDS);
        assertThat(restServer.liveEventViewers()).isZero();
    }
}
