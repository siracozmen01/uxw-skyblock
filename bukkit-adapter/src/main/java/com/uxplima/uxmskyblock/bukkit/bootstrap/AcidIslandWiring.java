package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.acid.AcidSeaStart;
import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The AcidIsland game mode, while the operator lets islands be AcidIsland islands. */
public final class AcidIslandWiring {

    private static final Logger LOGGER = Logger.getLogger(AcidIslandWiring.class.getName());

    private final AcidIslandConfiguration config;
    private final AcidIslandService service;
    private final SchedulerPort scheduler;

    public AcidIslandWiring(
            ConfigurationWiring configuration, PersistenceBootstrap persistence, SchedulerPort scheduler) {
        this.config = Objects.requireNonNull(configuration.acidIslandConfig(), "acidIslandConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new AcidIslandService(persistence.acidIslandsPort());
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " AcidIsland islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The AcidIsland islands could not be read ahead.", e);
            }
        });
    }

    public AcidIslandService service() {
        return service;
    }

    public AcidIslandConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that lays the acid sea, while AcidIsland is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new AcidSeaStart(service, scheduler, config.sea())) : List.of();
    }
}
