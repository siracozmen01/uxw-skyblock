package com.uxplima.uxmskyblock.persistence.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transport that actually puts a signed request on the wire.
 *
 * <p>It had no test at all. What it does with a redirect matters most: the request carries a SigV4
 * signature bound to the host and path it was signed for, so following one sends a signature that
 * cannot match to somewhere else, and hands the Authorization header to whatever host the redirect
 * named.
 */
class JavaHttpClientTransportTest {

    private HttpServer server;
    private final AtomicReference<Recorded> lastRequest = new AtomicReference<>();

    private record Recorded(String method, Map<String, List<String>> headers, String body) {}

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private URI uri(String path) {
        return URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + path);
    }

    private void handle(String path, int status, String body) {
        server.createContext(path, exchange -> {
            record(exchange);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequest.set(new Recorded(exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()), body));
    }

    @Test
    @DisplayName("The method, the headers and the body all reach the other end")
    void everythingReachesTheOtherEnd() throws Exception {
        handle("/bucket/key", 200, "ok");

        S3HttpResponse response = new JavaHttpClientTransport()
                .send(new S3HttpRequest(
                        "PUT",
                        uri("/bucket/key"),
                        Map.of("x-amz-content-sha256", List.of("abc")),
                        "hello".getBytes(StandardCharsets.UTF_8),
                        null,
                        5));

        assertThat(response.statusCode()).isEqualTo(200);
        Recorded seen = java.util.Objects.requireNonNull(lastRequest.get());
        assertThat(seen.method()).isEqualTo("PUT");
        assertThat(seen.body()).isEqualTo("hello");
        assertThat(seen.headers().get("X-amz-content-sha256")).containsExactly("abc");
        response.close();
    }

    @Test
    @DisplayName("A body handed over as a stream is sent too")
    void astreamedBodyIsSent() throws Exception {
        handle("/bucket/streamed", 200, "ok");

        S3HttpResponse response = new JavaHttpClientTransport()
                .send(new S3HttpRequest(
                        "PUT",
                        uri("/bucket/streamed"),
                        Map.of(),
                        new byte[0],
                        new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)),
                        8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(java.util.Objects.requireNonNull(lastRequest.get()).body()).isEqualTo("streamed");
        response.close();
    }

    @Test
    @DisplayName("A redirect is handed back as a redirect, never followed")
    void aredirectIsNotFollowed() throws Exception {
        server.createContext("/bucket/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/bucket/elsewhere");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        handle("/bucket/elsewhere", 200, "should not be reached");

        S3HttpResponse response =
                new JavaHttpClientTransport().send(S3HttpRequest.of("GET", uri("/bucket/moved"), Map.of()));

        assertThat(response.statusCode())
                .describedAs("a signature is bound to the host and path it was signed for")
                .isEqualTo(307);
        assertThat(lastRequest.get())
                .describedAs("the redirect target was never asked, so no Authorization went to it")
                .isNull();
        response.close();
    }

    @Test
    @DisplayName("A refusal is handed back with its status rather than thrown")
    void arefusalIsHandedBack() throws Exception {
        handle("/bucket/denied", 403, "<Error>SignatureDoesNotMatch</Error>");

        S3HttpResponse response =
                new JavaHttpClientTransport().send(S3HttpRequest.of("GET", uri("/bucket/denied"), Map.of()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.isSuccessful()).isFalse();
        response.close();
    }
}
