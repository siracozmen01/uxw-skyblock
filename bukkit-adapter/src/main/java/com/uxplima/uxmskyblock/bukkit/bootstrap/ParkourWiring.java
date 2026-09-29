package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.parkour.CourseStart;
import com.uxplima.uxmskyblock.bukkit.parkour.ParkourRuns;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The Parkour game mode, while the operator lets islands be Parkour courses. */
public final class ParkourWiring {

    private static final Logger LOGGER = Logger.getLogger(ParkourWiring.class.getName());

    private final ParkourConfiguration config;
    private final ParkourService service;
    private final SchedulerPort scheduler;
    private final ParkourRuns runs;

    public ParkourWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands) {
        this.config = Objects.requireNonNull(configuration.parkourConfig(), "parkourConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new ParkourService(persistence.parkourPort());
        this.runs = new ParkourRuns(
                service,
                islands,
                scheduler,
                config,
                configuration.messages(),
                configuration.effectsConfig(),
                new InteractionEffectPlayer(scheduler, configuration.messages()),
                Clock.systemUTC());
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " Parkour courses are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The Parkour courses could not be read ahead.", e);
            }
        });
    }

    public ParkourService service() {
        return service;
    }

    public ParkourRuns runs() {
        return runs;
    }

    public ParkourConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that makes an island a course, while Parkour is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new CourseStart(service, scheduler, config.markers())) : List.of();
    }
}
