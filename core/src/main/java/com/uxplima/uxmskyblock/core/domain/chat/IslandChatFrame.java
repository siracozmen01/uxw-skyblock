package com.uxplima.uxmskyblock.core.domain.chat;

import java.time.Instant;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * Ephemeral message transmission payload distributed across backend cluster nodes
 * for private island team communications.
 */
public record IslandChatFrame(
        IslandId islandId,
        ProfileId senderProfileId,
        String senderName,
        IslandRole senderRole,
        String message,
        Instant timestamp,
        IslandChatChannel channel) {

    public IslandChatFrame {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(senderProfileId, "senderProfileId must not be null");
        Objects.requireNonNull(senderName, "senderName must not be null");
        Objects.requireNonNull(senderRole, "senderRole must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        if (channel == IslandChatChannel.GLOBAL) {
            throw new IllegalArgumentException("A global message is the server's own chat, not a frame this carries");
        }
        if (senderName.isBlank()) {
            throw new IllegalArgumentException("senderName must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** A frame on the island's own channel, which is what every frame was before alliances had one. */
    public static IslandChatFrame island(
            IslandId islandId,
            ProfileId senderProfileId,
            String senderName,
            IslandRole senderRole,
            String message,
            Instant timestamp) {
        return new IslandChatFrame(
                islandId, senderProfileId, senderName, senderRole, message, timestamp, IslandChatChannel.ISLAND);
    }
}
