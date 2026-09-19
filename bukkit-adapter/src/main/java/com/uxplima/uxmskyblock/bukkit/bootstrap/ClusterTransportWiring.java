package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmlib.redis.LettuceRedisBus;
import com.uxplima.uxmlib.redis.LettuceRedisStreamBus;
import com.uxplima.uxmlib.redis.RedisBus;
import com.uxplima.uxmlib.redis.RedisStreamBus;
import com.uxplima.uxmskyblock.bukkit.chat.RedisIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.bukkit.config.ServerNodeConfiguration;
import com.uxplima.uxmskyblock.bukkit.event.RedisStreamsEventTransportAdapter;
import com.uxplima.uxmskyblock.bukkit.network.RedisClusterRoutingDirectoryAdapter;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.chat.LocalIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.application.event.LocalEventTransport;
import com.uxplima.uxmskyblock.core.application.network.ClusterRoutingDirectoryPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates cluster transport infrastructure (Redis Streams, pub/sub chat, cluster routing directory)
 * adhering to Sections 10, 11, 12, 13, and 14.
 *
 * <p>Enforces strict fail-closed semantics: when {@link ServerNodeConfiguration#isClustered()} is true,
 * any failure to establish or verify Redis connectivity throws {@link IllegalStateException} immediately,
 * prohibiting silent fallback to local/in-memory transports.
 */
public final class ClusterTransportWiring implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ClusterTransportWiring.class.getName());

    private final boolean clustered;
    private final @Nullable RedisClient redisClient;
    private final @Nullable RedisStreamBus redisStreamBus;
    private final @Nullable RedisBus redisBus;
    private final @Nullable StatefulRedisConnection<String, String> redisConnection;
    private final DurableEventTransportPort eventTransport;
    private final IslandChatTransportPort chatTransport;
    private final ClusterRoutingDirectoryPort clusterRoutingDirectory;

    public ClusterTransportWiring(
            boolean clustered,
            @Nullable RedisClient redisClient,
            @Nullable RedisStreamBus redisStreamBus,
            @Nullable RedisBus redisBus,
            @Nullable StatefulRedisConnection<String, String> redisConnection,
            DurableEventTransportPort eventTransport,
            IslandChatTransportPort chatTransport,
            ClusterRoutingDirectoryPort clusterRoutingDirectory) {
        this.clustered = clustered;
        this.redisClient = redisClient;
        this.redisStreamBus = redisStreamBus;
        this.redisBus = redisBus;
        this.redisConnection = redisConnection;
        this.eventTransport = Objects.requireNonNull(eventTransport, "eventTransport must not be null");
        this.chatTransport = Objects.requireNonNull(chatTransport, "chatTransport must not be null");
        this.clusterRoutingDirectory =
                Objects.requireNonNull(clusterRoutingDirectory, "clusterRoutingDirectory must not be null");
    }

    public static ClusterTransportWiring create(JavaPlugin plugin, ServerNodeConfiguration nodeConfig) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(nodeConfig, "nodeConfig must not be null");

        if (nodeConfig.isClustered()) {
            String redisUri = nodeConfig.redisUri();
            if (redisUri == null || redisUri.isBlank()) {
                throw new IllegalStateException(
                        "Fail-closed: Cluster mode is enabled but server-node.redis-uri is blank!");
            }

            try {
                LOGGER.info(() -> "Initializing clustered Redis transport connected to " + redisUri);
                RedisClient client = RedisClient.create(redisUri);
                LettuceRedisStreamBus streamBus = new LettuceRedisStreamBus(
                        client, msg -> plugin.getLogger().warning(msg));
                LettuceRedisBus bus =
                        new LettuceRedisBus(client, msg -> plugin.getLogger().warning(msg));
                StatefulRedisConnection<String, String> conn = client.connect();

                if (!streamBus.healthy() || !bus.healthy() || !conn.isOpen()) {
                    client.shutdown();
                    throw new IllegalStateException(
                            "Fail-closed: Redis connectivity probe failed on clustered startup for URI: " + redisUri);
                }

                DurableEventTransportPort eventTransport = new RedisStreamsEventTransportAdapter(streamBus);
                IslandChatTransportPort chatTransport = new RedisIslandChatTransportAdapter(bus);
                ClusterRoutingDirectoryPort routingDir = new RedisClusterRoutingDirectoryAdapter(conn);

                return new ClusterTransportWiring(
                        true, client, streamBus, bus, conn, eventTransport, chatTransport, routingDir);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Fail-closed: Failed to initialize clustered Redis infrastructure for URI: " + redisUri, e);
            }
        } else {
            return new ClusterTransportWiring(
                    false,
                    null,
                    null,
                    null,
                    null,
                    new LocalEventTransport(),
                    new LocalIslandChatTransportAdapter(),
                    new RedisClusterRoutingDirectoryAdapter());
        }
    }

    public boolean isClustered() {
        return clustered;
    }

    public DurableEventTransportPort eventTransport() {
        return eventTransport;
    }

    public IslandChatTransportPort chatTransport() {
        return chatTransport;
    }

    public ClusterRoutingDirectoryPort clusterRoutingDirectory() {
        return clusterRoutingDirectory;
    }

    @Override
    public void close() {
        try {
            eventTransport.close();
        } catch (Exception e) {
            LOGGER.warning(() -> "Error closing eventTransport: " + e.getMessage());
        }

        if (chatTransport instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                LOGGER.warning(() -> "Error closing chatTransport: " + e.getMessage());
            }
        }

        if (clusterRoutingDirectory instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                LOGGER.warning(() -> "Error closing clusterRoutingDirectory: " + e.getMessage());
            }
        }

        if (redisStreamBus != null) {
            try {
                redisStreamBus.close();
            } catch (Exception e) {
                LOGGER.warning(() -> "Error closing redisStreamBus: " + e.getMessage());
            }
        }

        if (redisBus != null) {
            try {
                redisBus.close();
            } catch (Exception e) {
                LOGGER.warning(() -> "Error closing redisBus: " + e.getMessage());
            }
        }

        if (redisConnection != null) {
            try {
                redisConnection.close();
            } catch (Exception e) {
                LOGGER.warning(() -> "Error closing redisConnection: " + e.getMessage());
            }
        }

        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                LOGGER.warning(() -> "Error shutting down redisClient: " + e.getMessage());
            }
        }
    }
}
