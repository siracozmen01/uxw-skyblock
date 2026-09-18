package com.uxplima.uxmskyblock.core.application.chat;

import java.util.function.Consumer;

import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;

/**
 * Cluster transport abstraction for publishing and receiving private island chat frames
 * across distributed server nodes (e.g. over Redis pub/sub or local event bus).
 */
public interface IslandChatTransportPort {

    /**
     * Publishes a chat frame across the cluster transport.
     *
     * @param frame message frame to distribute
     */
    void publish(IslandChatFrame frame);

    /**
     * Registers a listener to handle inbound frames received from the cluster transport.
     *
     * @param consumer frame handler
     */
    void subscribe(Consumer<IslandChatFrame> consumer);
}
