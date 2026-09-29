package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.brix.PlotStart;
import com.uxplima.uxmskyblock.bukkit.config.BrixConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.brix.BrixService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The Brix game mode, while the operator lets islands be Brix plots. */
public final class BrixWiring {

    private static final Logger LOGGER = Logger.getLogger(BrixWiring.class.getName());

    private final BrixConfiguration config;
    private final BrixService service;
    private final SchedulerPort scheduler;

    public BrixWiring(ConfigurationWiring configuration, PersistenceBootstrap persistence, SchedulerPort scheduler) {
        this.config = Objects.requireNonNull(configuration.brixConfig(), "brixConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new BrixService(persistence.brixPlotsPort());
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " Brix plots are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Brix plots could not be read ahead.", e);
            }
        });
    }

    public BrixService service() {
        return service;
    }

    public BrixConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that makes an island a plot, while Brix is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new PlotStart(service, scheduler, config.ground())) : List.of();
    }
}
