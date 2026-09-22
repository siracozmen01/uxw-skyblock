package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Production implementation of {@link S3HttpTransport} using standard {@link java.net.http.HttpClient}.
 *
 * <p>A redirect is never followed. The request carries a SigV4 signature bound to the host and path
 * it was signed for, so following one sends a signature that cannot match to somewhere else: at
 * best a 403 that hides the real answer, at worst the Authorization header handed to whatever host
 * the redirect named. S3 uses a redirect to say the endpoint or the region is wrong, and that is
 * worth seeing as itself rather than as a permission error.
 */
public final class JavaHttpClientTransport implements S3HttpTransport {

    private final HttpClient httpClient;

    public JavaHttpClientTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    public JavaHttpClientTransport(HttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public S3HttpResponse send(S3HttpRequest request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(request.uri());

        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                builder.header(entry.getKey(), value);
            }
        }

        HttpRequest.BodyPublisher publisher;
        if (request.bodyStream() != null) {
            publisher = HttpRequest.BodyPublishers.ofInputStream(request::bodyStream);
        } else if (request.body().length > 0) {
            publisher = HttpRequest.BodyPublishers.ofByteArray(request.body());
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }

        builder.method(request.method(), publisher);

        HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

        return new S3HttpResponse(response.statusCode(), response.headers().map(), response.body());
    }
}
