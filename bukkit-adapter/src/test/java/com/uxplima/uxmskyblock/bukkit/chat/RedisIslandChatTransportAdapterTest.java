package com.uxplima.uxmskyblock.bukkit.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmlib.redis.RedisBus;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisIslandChatTransportAdapterTest {

    private RedisBus redisBus;
    private RedisIslandChatTransportAdapter adapter;

    private final IslandChatFrame sampleFrame = IslandChatFrame.island(
            IslandId.of(UUID.randomUUID()),
            ProfileId.of(UUID.randomUUID()),
            "Steve",
            IslandRole.OWNER,
            "Hello from island team!",
            Instant.ofEpochMilli(1700000000000L));

    @BeforeEach
    void setUp() {
        redisBus = mock(RedisBus.class);
        adapter = new RedisIslandChatTransportAdapter(redisBus);
    }

    @Test
    @DisplayName("adapter subscribes to chat channel on initialization")
    void subscribesOnInitialization() {
        verify(redisBus).subscribe(eq(RedisIslandChatTransportAdapter.CHAT_CHANNEL), any());
    }

    @Test
    @DisplayName("publish serializes frame and sends via redis bus")
    void publishSerializesAndDispatches() {
        adapter.publish(sampleFrame);
        verify(redisBus).publish(eq(RedisIslandChatTransportAdapter.CHAT_CHANNEL), any());
    }

    @Test
    @DisplayName("round-trip binary serialization preserves all frame fields")
    void roundTripSerializationPreservesFields() throws IOException {
        byte[] bytes = RedisIslandChatTransportAdapter.serialize(sampleFrame);
        IslandChatFrame deserialized = RedisIslandChatTransportAdapter.deserialize(bytes);

        assertThat(deserialized.islandId()).isEqualTo(sampleFrame.islandId());
        assertThat(deserialized.senderProfileId()).isEqualTo(sampleFrame.senderProfileId());
        assertThat(deserialized.senderName()).isEqualTo(sampleFrame.senderName());
        assertThat(deserialized.senderRole()).isEqualTo(sampleFrame.senderRole());
        assertThat(deserialized.message()).isEqualTo(sampleFrame.message());
        assertThat(deserialized.timestamp()).isEqualTo(sampleFrame.timestamp());
    }

    @Test
    @DisplayName("handleInboundBytes decodes frame and notifies subscribers")
    void handleInboundBytesNotifiesSubscribers() throws IOException {
        AtomicReference<IslandChatFrame> received = new AtomicReference<>();
        adapter.subscribe(received::set);

        byte[] bytes = RedisIslandChatTransportAdapter.serialize(sampleFrame);
        adapter.handleInboundBytes(bytes);

        assertThat(received.get()).isEqualTo(sampleFrame);
    }
}
