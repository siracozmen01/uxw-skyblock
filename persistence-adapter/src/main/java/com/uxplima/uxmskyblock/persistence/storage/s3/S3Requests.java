package com.uxplima.uxmskyblock.persistence.storage.s3;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.storage.S3AddressingMode;
import com.uxplima.uxmskyblock.core.domain.storage.S3StorageConfiguration;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * Where an S3 request goes and how hard we try to get it there.
 *
 * <p>An endpoint may be addressed by path or by virtual host, and a bucket name lands in a
 * different part of the URI in each. A provider may also answer a 5xx that is worth retrying, and
 * one that is not worth retrying looks the same at the first attempt.
 *
 * <p>Both are the same question: how to talk to the provider. What to say is the adapter's job.
 */
final class S3Requests {

    private final S3StorageConfiguration configuration;
    private final S3HttpTransport transport;

    S3Requests(S3StorageConfiguration configuration, S3HttpTransport transport) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    /**
     * Signs a request and sends it, retrying a server side failure with a doubling backoff.
     *
     * <p>Signing happens here because every request needs it and an unsigned one is a 403 the
     * provider gives back at runtime, not a mistake the compiler can catch. There was one signing
     * line per call site and ten call sites.
     */
    S3HttpResponse send(S3HttpRequest unsigned) throws IOException {
        S3HttpRequest request =
                AwsSigV4Signer.sign(unsigned, configuration.credentials(), configuration.region(), Instant.now());
        int maxRetries = configuration.maxRetries();
        int attempts = 0;
        long backoffMs = 100;

        while (true) {
            attempts++;
            try {
                S3HttpResponse response = transport.send(request);
                if (response.statusCode() >= 500 && attempts <= maxRetries) {
                    response.close();
                    sleepBackoff(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                return response;
            } catch (IOException | InterruptedException e) {
                if (attempts > maxRetries) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IOException("S3 request interrupted after retries", e);
                    }
                    throw (IOException) e;
                }
                sleepBackoff(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private void sleepBackoff(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    URI bucketUri(StorageBucket bucket, @Nullable String query) {
        String bucketName = bucket.name();
        if (configuration.addressingMode() == S3AddressingMode.PATH_STYLE) {
            return assemble(host(), "/" + AwsSigV4Signer.rfc3986Encode(bucketName, false), query);
        }
        return assemble(bucketName + "." + host(), "/", query);
    }

    URI objectUri(StorageBucket bucket, String objectKey, @Nullable String query) {
        String sanitizedKey = sanitizeKey(objectKey);
        if (configuration.pathPrefix() != null && !configuration.pathPrefix().isBlank()) {
            sanitizedKey = configuration.pathPrefix().trim() + "/" + sanitizedKey;
        }
        String encodedKey = AwsSigV4Signer.rfc3986Encode(sanitizedKey, true);
        String bucketName = bucket.name();

        if (configuration.addressingMode() == S3AddressingMode.PATH_STYLE) {
            return assemble(host(), "/" + AwsSigV4Signer.rfc3986Encode(bucketName, false) + "/" + encodedKey, query);
        }
        return assemble(bucketName + "." + host(), "/" + encodedKey, query);
    }

    /** The endpoint's host, with its port when it names one. */
    private String host() {
        URI base = configuration.endpoint();
        return base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort();
    }

    /**
     * Builds the URI out of parts that are already encoded.
     *
     * <p>{@code new URI(scheme, host, path, query, fragment)} encodes the path its own way, and its
     * way is not the signer's: it leaves a plus and a comma alone where RFC 3986 encoding does not.
     * The request then went to one path and was signed over another. Everything here is encoded
     * once, by the encoder the signature uses, and handed over already finished.
     */
    private URI assemble(String host, String encodedPath, @Nullable String encodedQuery) {
        URI base = configuration.endpoint();
        String scheme = base.getScheme() != null ? base.getScheme() : "https";
        String uri = scheme + "://" + host + encodedPath;
        if (encodedQuery != null && !encodedQuery.isEmpty()) {
            uri = uri + "?" + encodedQuery;
        }
        try {
            return URI.create(uri);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URI construction for " + uri, e);
        }
    }

    String sanitizeKey(String objectKey) {
        String sanitized = objectKey.replace('\\', '/');
        while (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }
        if (sanitized.contains("..")) {
            throw new IllegalArgumentException("Path traversal attempt in S3 key: " + objectKey);
        }
        return sanitized;
    }
}
