package com.uxplima.uxmskyblock.bukkit.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmlib.redis.RedisBus;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatChannel;
import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * Distributed implementation of {@link IslandChatTransportPort} backed by {@link RedisBus}.
 * Distributes chat frames across cluster nodes using binary Redis pub/sub adhering to Section 13.
 */
public final class RedisIslandChatTransportAdapter implements IslandChatTransportPort, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(RedisIslandChatTransportAdapter.class.getName());
    public static final String CHAT_CHANNEL = "uxmskyblock:chat:island";

    private final RedisBus redisBus;
    private final List<Consumer<IslandChatFrame>> subscribers = new CopyOnWriteArrayList<>();

    public RedisIslandChatTransportAdapter(RedisBus redisBus) {
        this.redisBus = Objects.requireNonNull(redisBus, "redisBus must not be null");
        this.redisBus.subscribe(CHAT_CHANNEL, this::handleInboundBytes);
    }

    @Override
    public void publish(IslandChatFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        try {
            byte[] bytes = serialize(frame);
            redisBus.publish(CHAT_CHANNEL, bytes);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to serialize IslandChatFrame for redis publication", e);
        }
    }

    @Override
    public void subscribe(Consumer<IslandChatFrame> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        subscribers.add(consumer);
    }

    public void handleInboundBytes(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        try {
            IslandChatFrame frame = deserialize(payload);
            for (Consumer<IslandChatFrame> subscriber : subscribers) {
                try {
                    subscriber.accept(frame);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error dispatching inbound chat frame to subscriber", e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to deserialize inbound IslandChatFrame from redis pub/sub", e);
        }
    }

    public static byte[] serialize(IslandChatFrame frame) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF(frame.islandId().value().toString());
            dos.writeUTF(frame.senderProfileId().value().toString());
            dos.writeUTF(frame.senderName());
            dos.writeUTF(frame.senderRole().id());
            dos.writeUTF(frame.message());
            dos.writeLong(frame.timestamp().toEpochMilli());
            dos.writeUTF(frame.channel().name());
        }
        return baos.toByteArray();
    }

    public static IslandChatFrame deserialize(byte[] bytes) throws IOException {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes))) {
            IslandId islandId = IslandId.fromString(dis.readUTF());
            ProfileId profileId = ProfileId.fromString(dis.readUTF());
            String senderName = dis.readUTF();
            IslandRole senderRole = IslandRole.byId(dis.readUTF()).orElse(IslandRole.MEMBER);
            String message = dis.readUTF();
            Instant timestamp = Instant.ofEpochMilli(dis.readLong());
            // A frame written by a node that predates the alliance channel has nothing after the
            // timestamp. It is an island frame, which is what every frame was then.
            IslandChatChannel channel = readChannelOrIsland(dis);
            return new IslandChatFrame(islandId, profileId, senderName, senderRole, message, timestamp, channel);
        }
    }

    /** The channel the frame names, or the island's own when the writer did not name one. */
    private static IslandChatChannel readChannelOrIsland(DataInputStream dis) {
        try {
            return IslandChatChannel.valueOf(dis.readUTF());
        } catch (IOException | IllegalArgumentException older) {
            return IslandChatChannel.ISLAND;
        }
    }

    public boolean isHealthy() {
        return redisBus.healthy();
    }

    @Override
    public void close() {
        subscribers.clear();
        redisBus.close();
    }
}
