package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The StrangerRealms game mode, while the operator lets islands be StrangerRealms islands. */
public final class StrangerRealmsWiring {

    private static final Logger LOGGER = Logger.getLogger(StrangerRealmsWiring.class.getName());

    private final StrangerRealmsConfiguration config;
    private final StrangerRealmsService service;
    private final SchedulerPort scheduler;
    private final String upsideDownWorld;

    public StrangerRealmsWiring(
            ConfigurationWiring configuration, PersistenceBootstrap persistence, SchedulerPort scheduler) {
        this.config =
                Objects.requireNonNull(configuration.strangerRealmsConfig(), "strangerRealmsConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new StrangerRealmsService(persistence.strangerRealmsPort());
        DimensionMapping nether = configuration.dimensionConfig().mappings().get(IslandDimensionType.NETHER);
        this.upsideDownWorld = nether == null
                ? com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration.DEFAULT_NETHER_WORLD
                : nether.worldName();
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " StrangerRealms islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The StrangerRealms islands could not be read ahead.", e);
            }
        });
    }

    public StrangerRealmsService service() {
        return service;
    }

    public StrangerRealmsConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that mirrors the land into the Upside Down, while StrangerRealms is enabled. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled()
                ? List.of(new UpsideDownStart(service, scheduler, config.upsideDown(), () -> upsideDownWorld))
                : List.of();
    }
}
