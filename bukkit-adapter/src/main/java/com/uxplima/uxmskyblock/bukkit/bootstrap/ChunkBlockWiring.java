package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockStart;
import com.uxplima.uxmskyblock.bukkit.config.ChunkBlockConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkBlockService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The ChunkBlock game mode, while the operator lets islands be ChunkBlock islands. */
public final class ChunkBlockWiring {

    private static final Logger LOGGER = Logger.getLogger(ChunkBlockWiring.class.getName());

    private final ChunkBlockConfiguration config;
    private final ChunkBlockService service;
    private final com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener;

    public ChunkBlockWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands) {
        this.config = Objects.requireNonNull(configuration.chunkBlockConfig(), "chunkBlockConfig must not be null");
        this.service = new ChunkBlockService(persistence.chunkTerritoryPort(), config.rules());
        this.listener = new com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener(
                service,
                islands,
                scheduler,
                configuration.messages(),
                persistence.islandStoragePort()::findLocationByIslandId,
                config.bypassPermission());
        // A fallen level closes chunks off the main thread; whoever stands in one is moved out.
        service.whenClosed(listener::moveOut);
        // Every territory into memory before anybody steps near a closed chunk, so no step is a query.
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " ChunkBlock islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The ChunkBlock islands could not be read ahead.", e);
            }
        });
    }

    public ChunkBlockService service() {
        return service;
    }

    /** The edge of every territory, registered while the operator lets islands be ChunkBlock islands. */
    public com.uxplima.uxmskyblock.bukkit.chunkblock.ChunkBlockListener listener() {
        return listener;
    }

    public ChunkBlockConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /**
     * The action that starts an island's territory, while ChunkBlock is enabled, and none otherwise,
     * so a ChunkBlock preset is not offered.
     */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new ChunkBlockStart(service)) : List.of();
    }
}
