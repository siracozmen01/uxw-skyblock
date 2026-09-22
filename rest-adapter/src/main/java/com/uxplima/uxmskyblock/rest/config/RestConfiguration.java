package com.uxplima.uxmskyblock.rest.config;

import java.time.Duration;
import java.util.Objects;

/**
 * What the REST module needs to open a port, knowing nothing of the plugin's configuration file.
 *
 * @param idempotencyRetention how long a deposit's idempotency key is remembered, so a retry of a
 *     deposit already made is answered rather than made again
 * @param idempotencyCapacity the most keys a node will hold at once, because the key comes from the
 *     caller and a caller with the token can send an unlimited number of different ones
 */
public record RestConfiguration(
        boolean enabled,
        String host,
        int port,
        String bearerToken,
        Duration idempotencyRetention,
        int idempotencyCapacity) {

    /** Long enough for any retry a web store makes, short enough that a day of them is not held. */
    public static final Duration DEFAULT_IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    /** A hard ceiling, so a caller cannot grow the map for as long as the process runs. */
    public static final int DEFAULT_IDEMPOTENCY_CAPACITY = 10_000;

    public RestConfiguration {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(bearerToken, "bearerToken must not be null");
        Objects.requireNonNull(idempotencyRetention, "idempotencyRetention must not be null");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535: " + port);
        }
        if (idempotencyRetention.isNegative() || idempotencyRetention.isZero()) {
            throw new IllegalArgumentException("idempotencyRetention must be positive: " + idempotencyRetention);
        }
        if (idempotencyCapacity < 1) {
            throw new IllegalArgumentException("idempotencyCapacity must be positive: " + idempotencyCapacity);
        }
    }

    public RestConfiguration(boolean enabled, String host, int port, String bearerToken) {
        this(enabled, host, port, bearerToken, DEFAULT_IDEMPOTENCY_RETENTION, DEFAULT_IDEMPOTENCY_CAPACITY);
    }

    public static RestConfiguration createDefault() {
        return new RestConfiguration(false, "127.0.0.1", 8080, "changeme-uxm-skyblock-secret");
    }
}
