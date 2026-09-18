package com.uxplima.uxmskyblock.core.application.chat;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.uxplima.uxmskyblock.core.domain.chat.IslandChatFrame;

/**
 * Thread-safe local in-memory pub/sub transport adapter for standalone servers
 * or fallback when distributed cluster transport is unattached.
 */
public final class LocalIslandChatTransportAdapter implements IslandChatTransportPort {

    private final List<Consumer<IslandChatFrame>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(IslandChatFrame frame) {
        Objects.requireNonNull(frame, "frame must not be null");
        for (Consumer<IslandChatFrame> subscriber : subscribers) {
            subscriber.accept(frame);
        }
    }

    @Override
    public void subscribe(Consumer<IslandChatFrame> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        subscribers.add(consumer);
    }
}
