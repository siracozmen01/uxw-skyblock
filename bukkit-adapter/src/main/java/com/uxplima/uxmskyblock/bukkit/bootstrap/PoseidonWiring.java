package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.poseidon.OceanStart;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The Poseidon game mode, while the operator lets islands be Poseidon islands. */
public final class PoseidonWiring {

    private static final Logger LOGGER = Logger.getLogger(PoseidonWiring.class.getName());

    private final PoseidonConfiguration config;
    private final PoseidonService service;
    private final SchedulerPort scheduler;

    public PoseidonWiring(
            ConfigurationWiring configuration, PersistenceBootstrap persistence, SchedulerPort scheduler) {
        this.config = Objects.requireNonNull(configuration.poseidonConfig(), "poseidonConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new PoseidonService(persistence.poseidonIslandsPort());
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " Poseidon islands are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Poseidon islands could not be read ahead.", e);
            }
        });
    }

    public PoseidonService service() {
        return service;
    }

    public PoseidonConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that lays the ocean, while Poseidon is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new OceanStart(service, scheduler, config.ocean())) : List.of();
    }
}
