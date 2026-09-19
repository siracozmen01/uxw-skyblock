package com.uxplima.uxmskyblock.bukkit.network;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.network.ClusterRoutingDirectoryPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise cluster-wide island routing directory supporting TTL expiration,
 * monotonic epoch fencing validation, and optional Redis backend distribution adhering to Section 14.
 */
public final class RedisClusterRoutingDirectoryAdapter implements ClusterRoutingDirectoryPort, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RedisClusterRoutingDirectoryAdapter.class.getName());
    public static final String KEY_PREFIX = "uxmskyblock:routing:";

    public record RouteEntry(ServerNodeId nodeId, long epoch, long expiresAtMillis) {
        public RouteEntry {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
        }

        public boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }

    private final Map<IslandId, RouteEntry> localCache = new ConcurrentHashMap<>();
    private final @Nullable StatefulRedisConnection<String, String> connection;
    private final @Nullable RedisCommands<String, String> commands;

    public RedisClusterRoutingDirectoryAdapter(StatefulRedisConnection<String, String> connection) {
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.commands = connection.sync();
    }

    public RedisClusterRoutingDirectoryAdapter(RedisCommands<String, String> commands) {
        this.connection = null;
        this.commands = Objects.requireNonNull(commands, "commands must not be null");
    }

    public RedisClusterRoutingDirectoryAdapter() {
        this.connection = null;
        this.commands = null;
    }

    @Override
    public Optional<ServerNodeId> findAuthoritativeNode(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        long now = System.currentTimeMillis();

        RouteEntry cached = localCache.get(islandId);
        if (cached != null) {
            if (!cached.isExpired(now)) {
                return Optional.of(cached.nodeId());
            } else {
                localCache.remove(islandId, cached);
            }
        }

        if (commands != null) {
            try {
                String key = KEY_PREFIX + islandId.value().toString();
                String val = commands.get(key);
                if (val != null && val.contains(":")) {
                    String[] parts = val.split(":", 2);
                    ServerNodeId nodeId = ServerNodeId.of(parts[0]);
                    long epoch = Long.parseLong(parts[1]);

                    Long pttl = commands.pttl(key);
                    long remainingTtl = (pttl != null && pttl > 0) ? pttl : 5000L;
                    localCache.put(islandId, new RouteEntry(nodeId, epoch, now + remainingTtl));
                    return Optional.of(nodeId);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to query Redis for island routing directory: " + islandId, e);
            }
        }

        return Optional.empty();
    }

    @Override
    public void cacheRoute(IslandId islandId, ServerNodeId nodeId, long epoch, Duration ttl) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");

        long now = System.currentTimeMillis();
        long expiresAtMillis = now + Math.max(1000L, ttl.toMillis());

        localCache.compute(islandId, (id, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                // Reject stale updates with older epoch (monotonic fencing violation)
                if (epoch < existing.epoch()) {
                    LOGGER.warning(() -> "Rejected stale routing update for island " + islandId + ": incoming epoch "
                            + epoch + " < current epoch " + existing.epoch());
                    return existing;
                }
            }
            return new RouteEntry(nodeId, epoch, expiresAtMillis);
        });

        if (commands != null) {
            try {
                String key = KEY_PREFIX + islandId.value().toString();
                long seconds = Math.max(1L, ttl.toSeconds());
                commands.setex(key, seconds, nodeId.value() + ":" + epoch);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to cache route in Redis for island: " + islandId, e);
            }
        }
    }

    @Override
    public void invalidateRoute(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        localCache.remove(islandId);

        if (commands != null) {
            try {
                String key = KEY_PREFIX + islandId.value().toString();
                commands.del(key);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to invalidate route in Redis for island: " + islandId, e);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        if (connection != null) {
            return connection.isOpen();
        }
        return true;
    }

    @Override
    public void close() {
        localCache.clear();
        if (connection != null) {
            connection.close();
        }
    }
}
