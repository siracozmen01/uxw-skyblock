package com.uxplima.uxmskyblock.bukkit.config;

import java.time.Duration;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Immutable configuration record specifying server cluster node identity, default world parameters,
 * and cluster transport configuration.
 *
 * <p>Fails fast at bootstrap if server node identity is missing or unconfigured, preventing
 * split-brain or node collision issues in multi-server distributed deployments.
 */
public record ServerNodeConfiguration(
        ServerNodeId nodeId,
        String worldName,
        boolean clustered,
        String redisUri,
        Duration routeCacheTtl,
        Duration authorityLease,
        Duration authorityHeartbeatInterval) {

    /**
     * How long a route to another node stays cached, when the operator names no other number.
     *
     * <p>It bounds how long a visitor can be sent to a node that has since lost authority over the
     * island they asked for. Fifteen seconds is the shipped answer; a busy cluster may want less.
     */
    public static final Duration DEFAULT_ROUTE_CACHE_TTL = Duration.ofSeconds(15);

    /**
     * How long this node's authority over an island runs before it must be pushed forward.
     *
     * <p>It is also how long an island waits before another node may pick it up after this one
     * stops. A shorter lease recovers faster and beats harder on the database.
     */
    public static final Duration DEFAULT_AUTHORITY_LEASE = Duration.ofMinutes(10);

    /** How often the lease is pushed forward. Well inside the lease, so one missed pass is survivable. */
    public static final Duration DEFAULT_AUTHORITY_HEARTBEAT_INTERVAL = Duration.ofMinutes(2);

    public static final String DEFAULT_WORLD_NAME = "world";
    public static final String DEFAULT_REDIS_URI = "redis://localhost:6379";

    public ServerNodeConfiguration {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(redisUri, "redisUri must not be null");
        Objects.requireNonNull(routeCacheTtl, "routeCacheTtl must not be null");
        Objects.requireNonNull(authorityLease, "authorityLease must not be null");
        Objects.requireNonNull(authorityHeartbeatInterval, "authorityHeartbeatInterval must not be null");
        if (authorityLease.toSeconds() < 1) {
            throw new IllegalArgumentException("authority-lease must be at least a second: " + authorityLease);
        }
        if (authorityHeartbeatInterval.toSeconds() < 1) {
            throw new IllegalArgumentException(
                    "authority-heartbeat-interval must be at least a second: " + authorityHeartbeatInterval);
        }
        if (authorityHeartbeatInterval.compareTo(authorityLease) >= 0) {
            throw new IllegalArgumentException("authority-heartbeat-interval (" + authorityHeartbeatInterval
                    + ") must be shorter than authority-lease (" + authorityLease
                    + "), or the lease runs out between two beats");
        }
        if (worldName.isBlank()) {
            throw new IllegalArgumentException("world-name must not be blank");
        }
        if (clustered && redisUri.isBlank()) {
            throw new IllegalArgumentException("redis-uri must not be blank when clustered is true");
        }
    }

    public ServerNodeConfiguration(ServerNodeId nodeId, String worldName) {
        this(
                nodeId,
                worldName,
                false,
                "",
                DEFAULT_ROUTE_CACHE_TTL,
                DEFAULT_AUTHORITY_LEASE,
                DEFAULT_AUTHORITY_HEARTBEAT_INTERVAL);
    }

    public static ServerNodeConfiguration of(ServerNodeId nodeId, String worldName) {
        return new ServerNodeConfiguration(
                nodeId,
                worldName,
                false,
                "",
                DEFAULT_ROUTE_CACHE_TTL,
                DEFAULT_AUTHORITY_LEASE,
                DEFAULT_AUTHORITY_HEARTBEAT_INTERVAL);
    }

    public static ServerNodeConfiguration of(String nodeId, String worldName) {
        return new ServerNodeConfiguration(
                ServerNodeId.of(nodeId),
                worldName,
                false,
                "",
                DEFAULT_ROUTE_CACHE_TTL,
                DEFAULT_AUTHORITY_LEASE,
                DEFAULT_AUTHORITY_HEARTBEAT_INTERVAL);
    }

    public static ServerNodeConfiguration of(
            ServerNodeId nodeId, String worldName, boolean clustered, String redisUri) {
        return new ServerNodeConfiguration(
                nodeId,
                worldName,
                clustered,
                redisUri != null ? redisUri : "",
                DEFAULT_ROUTE_CACHE_TTL,
                DEFAULT_AUTHORITY_LEASE,
                DEFAULT_AUTHORITY_HEARTBEAT_INTERVAL);
    }

    public boolean isClustered() {
        return clustered;
    }

    /**
     * Translates a {@link ConfigurationNode} into a validated {@link ServerNodeConfiguration}.
     *
     * @param rootNode the root configuration node
     * @return validated server node configuration
     * @throws IllegalStateException if the node identity is missing or blank
     * @throws IllegalArgumentException if configured attributes violate invariants
     */
    public static ServerNodeConfiguration load(ConfigurationNode rootNode) {
        Objects.requireNonNull(rootNode, "rootNode must not be null");

        ConfigurationNode nodeConfig = rootNode.node("server-node");
        String rawNodeId = nodeConfig.node("id").getString();
        if (rawNodeId == null || rawNodeId.isBlank()) {
            rawNodeId = rootNode.node("node-id").getString();
        }

        if (rawNodeId == null || rawNodeId.isBlank()) {
            throw new IllegalStateException(
                    "Server node identity is missing or blank! A non-empty 'server-node.id' must be configured in configuration.");
        }

        String rawWorldName = nodeConfig.node("world-name").getString();
        if (rawWorldName == null) {
            rawWorldName = rootNode.node("world-name").getString();
        }

        String worldName;
        if (rawWorldName == null) {
            worldName = DEFAULT_WORLD_NAME;
        } else if (rawWorldName.isBlank()) {
            throw new IllegalArgumentException("world-name must not be blank");
        } else {
            worldName = rawWorldName.trim();
        }

        boolean clustered = nodeConfig.node("clustered").getBoolean(false);
        String envClustered = System.getenv("SKYBLOCK_CLUSTERED");
        if (envClustered != null && !envClustered.isBlank()) {
            clustered = Boolean.parseBoolean(envClustered.trim());
        }

        String rawRedisUri = nodeConfig.node("redis-uri").getString();
        if (rawRedisUri == null || rawRedisUri.isBlank()) {
            rawRedisUri = rootNode.node("redis", "uri").getString();
        }
        String envRedisUri = System.getenv("SKYBLOCK_REDIS_URI");
        if (envRedisUri != null && !envRedisUri.isBlank()) {
            rawRedisUri = envRedisUri.trim();
        }
        String redisUri = (rawRedisUri != null && !rawRedisUri.isBlank())
                ? rawRedisUri.trim()
                : (clustered ? DEFAULT_REDIS_URI : "");

        Duration routeCacheTtl = parseSeconds(nodeConfig.node("route-cache-ttl"), DEFAULT_ROUTE_CACHE_TTL);
        Duration authorityLease = parseSeconds(nodeConfig.node("authority-lease"), DEFAULT_AUTHORITY_LEASE);
        Duration heartbeat =
                parseSeconds(nodeConfig.node("authority-heartbeat-interval"), DEFAULT_AUTHORITY_HEARTBEAT_INTERVAL);
        return new ServerNodeConfiguration(
                ServerNodeId.of(rawNodeId.trim()),
                worldName,
                clustered,
                redisUri,
                routeCacheTtl,
                authorityLease,
                heartbeat);
    }

    /**
     * Reads a window written either as a plain number of seconds or with a unit suffix.
     *
     * <p>An unreadable value falls back rather than refusing to start: a server that will not boot
     * over a cache window is a worse outcome than one that boots with the shipped number.
     */
    private static Duration parseSeconds(ConfigurationNode node, Duration fallback) {
        String raw = node.getString();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.strip().toLowerCase(java.util.Locale.ROOT);
        try {
            if (trimmed.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(
                        trimmed.substring(0, trimmed.length() - 1).strip()));
            }
            if (trimmed.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(
                        trimmed.substring(0, trimmed.length() - 1).strip()));
            }
            if (trimmed.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(
                        trimmed.substring(0, trimmed.length() - 1).strip()));
            }
            return Duration.ofSeconds(Long.parseLong(trimmed));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
