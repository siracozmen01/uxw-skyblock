package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * HTTP response representation from an S3-compatible endpoint.
 */
public final class S3HttpResponse implements AutoCloseable {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final @Nullable InputStream bodyStream;

    public S3HttpResponse(int statusCode, Map<String, List<String>> headers, @Nullable InputStream bodyStream) {
        this.statusCode = statusCode;
        this.headers = Collections.unmodifiableMap(Objects.requireNonNull(headers, "headers"));
        this.bodyStream = bodyStream;
    }

    public static S3HttpResponse of(int statusCode, Map<String, List<String>> headers, byte[] bodyBytes) {
        return new S3HttpResponse(statusCode, headers, new ByteArrayInputStream(bodyBytes));
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isSuccessful() {
        return statusCode >= 200 && statusCode < 300;
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Optional<String> firstHeader(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    return Optional.ofNullable(values.get(0));
                }
            }
        }
        return Optional.empty();
    }

    public @Nullable InputStream bodyStream() {
        return bodyStream;
    }

    public byte[] bodyBytes() throws IOException {
        if (bodyStream == null) {
            return new byte[0];
        }
        try (InputStream in = bodyStream) {
            return in.readAllBytes();
        }
    }

    public String bodyString() throws IOException {
        return new String(bodyBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        if (bodyStream != null) {
            bodyStream.close();
        }
    }
}
