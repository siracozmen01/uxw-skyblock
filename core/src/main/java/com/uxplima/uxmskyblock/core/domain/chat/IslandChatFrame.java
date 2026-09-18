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
        Instant timestamp) {

    public IslandChatFrame {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(senderProfileId, "senderProfileId must not be null");
        Objects.requireNonNull(senderName, "senderName must not be null");
        Objects.requireNonNull(senderRole, "senderRole must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (senderName.isBlank()) {
            throw new IllegalArgumentException("senderName must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
