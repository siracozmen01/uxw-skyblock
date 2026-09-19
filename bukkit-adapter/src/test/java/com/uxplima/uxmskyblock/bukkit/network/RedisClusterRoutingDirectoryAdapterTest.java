package com.uxplima.uxmskyblock.bukkit.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisClusterRoutingDirectoryAdapterTest {

    private RedisCommands<String, String> commands;
    private RedisClusterRoutingDirectoryAdapter adapter;

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final ServerNodeId node1 = ServerNodeId.of("node-1");
    private final ServerNodeId node2 = ServerNodeId.of("node-2");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        commands = mock(RedisCommands.class);
        adapter = new RedisClusterRoutingDirectoryAdapter(commands);
    }

    @Test
    @DisplayName("caches and finds authoritative node")
    void cachesAndFindsAuthoritativeNode() {
        adapter.cacheRoute(islandId, node1, 10L, Duration.ofMinutes(5));

        Optional<ServerNodeId> result = adapter.findAuthoritativeNode(islandId);
        assertThat(result).contains(node1);
        verify(commands)
                .setex(
                        eq(RedisClusterRoutingDirectoryAdapter.KEY_PREFIX + islandId.value()),
                        eq(300L),
                        eq("node-1:10"));
    }

    @Test
    @DisplayName("rejects stale routing update with smaller epoch")
    void rejectsStaleEpoch() {
        adapter.cacheRoute(islandId, node1, 10L, Duration.ofMinutes(5));

        // Attempt update with older epoch 5
        adapter.cacheRoute(islandId, node2, 5L, Duration.ofMinutes(5));

        Optional<ServerNodeId> result = adapter.findAuthoritativeNode(islandId);
        assertThat(result).contains(node1); // Stale update rejected, still node1
    }

    @Test
    @DisplayName("accepts routing update with higher epoch")
    void acceptsHigherEpoch() {
        adapter.cacheRoute(islandId, node1, 10L, Duration.ofMinutes(5));

        // Update with newer epoch 15
        adapter.cacheRoute(islandId, node2, 15L, Duration.ofMinutes(5));

        Optional<ServerNodeId> result = adapter.findAuthoritativeNode(islandId);
        assertThat(result).contains(node2);
    }

    @Test
    @DisplayName("invalidates route locally and in redis")
    void invalidatesRoute() {
        adapter.cacheRoute(islandId, node1, 10L, Duration.ofMinutes(5));
        adapter.invalidateRoute(islandId);

        when(commands.get(RedisClusterRoutingDirectoryAdapter.KEY_PREFIX + islandId.value()))
                .thenReturn(null);
        Optional<ServerNodeId> result = adapter.findAuthoritativeNode(islandId);

        assertThat(result).isEmpty();
        verify(commands).del(eq(RedisClusterRoutingDirectoryAdapter.KEY_PREFIX + islandId.value()));
    }

    @Test
    @DisplayName("reads from redis when local cache misses")
    void readsFromRedisOnLocalCacheMiss() {
        when(commands.get(RedisClusterRoutingDirectoryAdapter.KEY_PREFIX + islandId.value()))
                .thenReturn("node-2:25");
        when(commands.pttl(RedisClusterRoutingDirectoryAdapter.KEY_PREFIX + islandId.value()))
                .thenReturn(60000L);

        Optional<ServerNodeId> result = adapter.findAuthoritativeNode(islandId);
        assertThat(result).contains(node2);
    }
}
