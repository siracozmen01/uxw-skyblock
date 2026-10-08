package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.tradewinds.VesselStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The TradeWinds game mode, while the operator lets islands be trading vessels. */
public final class TradeWindsWiring {

    private static final Logger LOGGER = Logger.getLogger(TradeWindsWiring.class.getName());

    private final TradeWindsConfiguration config;
    private final VesselService service;
    private final SchedulerPort scheduler;

    public TradeWindsWiring(
            ConfigurationWiring configuration, PersistenceBootstrap persistence, SchedulerPort scheduler) {
        this.config = Objects.requireNonNull(configuration.tradeWindsConfig(), "tradeWindsConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new VesselService(persistence.vesselsPort());
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " TradeWinds vessels are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The TradeWinds vessels could not be read ahead.", e);
            }
        });
    }

    public VesselService service() {
        return service;
    }

    public TradeWindsConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that launches an island as a vessel, while TradeWinds is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new VesselStart(service, scheduler, config.sea())) : List.of();
    }
}
