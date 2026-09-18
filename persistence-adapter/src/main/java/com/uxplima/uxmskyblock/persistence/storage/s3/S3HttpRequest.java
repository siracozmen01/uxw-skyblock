package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Immutable HTTP request representation for S3 operations.
 */
public final class S3HttpRequest {

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final @Nullable InputStream bodyStream;
    private final long contentLength;

    public S3HttpRequest(
            String method,
            URI uri,
            Map<String, List<String>> headers,
            byte[] body,
            @Nullable InputStream bodyStream,
            long contentLength) {
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.uri = Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body != null ? body.clone() : new byte[0];
        this.bodyStream = bodyStream;
        this.contentLength = contentLength;
    }

    public static S3HttpRequest of(String method, URI uri, Map<String, List<String>> headers) {
        return new S3HttpRequest(method, uri, headers, new byte[0], null, 0);
    }

    public static S3HttpRequest of(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
        Objects.requireNonNull(body, "body must not be null");
        return new S3HttpRequest(method, uri, headers, body, null, body.length);
    }

    public static S3HttpRequest ofStream(
            String method, URI uri, Map<String, List<String>> headers, InputStream stream, long contentLength) {
        Objects.requireNonNull(stream, "stream must not be null");
        return new S3HttpRequest(method, uri, headers, new byte[0], stream, contentLength);
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public byte[] body() {
        return body.clone();
    }

    public @Nullable InputStream bodyStream() {
        return bodyStream;
    }

    public long contentLength() {
        return contentLength;
    }
}
