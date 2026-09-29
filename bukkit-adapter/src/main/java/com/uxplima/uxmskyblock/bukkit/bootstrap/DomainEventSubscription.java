package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.event.DurableEventTransportPort;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/** This node listening for what other nodes did to the islands it remembers. */
final class DomainEventSubscription {

    private static final Logger LOGGER = Logger.getLogger(DomainEventSubscription.class.getName());

    private DomainEventSubscription() {}

    /**
     * Listens for what other nodes did to islands this node remembers something about.
     *
     * <p>A transport that cannot be subscribed to is not a failure to start: a single node server
     * has nobody to hear from, and the local transport delivers to this node's own consumers
     * already.
     */
    static @Nullable AutoCloseable subscribe(
            ServerNodeId serverNodeId,
            DurableEventTransportPort eventTransport,
            GameplayWiring gameplay,
            PersistenceBootstrap persistence) {
        String node = serverNodeId.value();
        com.uxplima.uxmskyblock.core.application.event.OutboxEventConsumer forgetting =
                new com.uxplima.uxmskyblock.core.application.event.ClusterIslandCacheInvalidation(islandId -> {
                    gameplay.cacheEviction().forget(islandId);
                    gameplay.protectionListener().invalidateIsland(islandId);
                });
        try {
            return eventTransport.subscribe(
                    "uxmskyblock:stream:domain_events",
                    node,
                    node + "-cache-invalidation",
                    new com.uxplima.uxmskyblock.core.application.event.InboxDeduplicatingConsumer(
                            node + "-cache-invalidation", persistence.consumerInboxPort(), forgetting));
        } catch (RuntimeException notSubscribable) {
            LOGGER.warning(
                    () -> "Nothing is listening for island changes from other nodes: " + notSubscribable.getMessage());
            return null;
        }
    }
}
