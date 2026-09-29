package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.event.entity.CreatureSpawnEvent;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.stranger.UpsideDownSpawns;
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
    private final UpsideDownSpawns spawns;
    private final com.uxplima.uxmskyblock.bukkit.stranger.Glimmer glimmer;

    public StrangerRealmsWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            SchedulerPort scheduler,
            IslandProtectionListener islands) {
        this.config =
                Objects.requireNonNull(configuration.strangerRealmsConfig(), "strangerRealmsConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new StrangerRealmsService(persistence.strangerRealmsPort());
        DimensionMapping nether = configuration.dimensionConfig().mappings().get(IslandDimensionType.NETHER);
        this.upsideDownWorld = nether == null
                ? com.uxplima.uxmskyblock.bukkit.config.DimensionConfiguration.DEFAULT_NETHER_WORLD
                : nether.worldName();
        this.spawns = new UpsideDownSpawns(
                service,
                islands,
                config.mobs(),
                () -> upsideDownWorld,
                configuration::islandWorlds,
                (at, type) -> at.getWorld().spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.CUSTOM));
        this.glimmer = new com.uxplima.uxmskyblock.bukkit.stranger.Glimmer(
                service, islands, scheduler, config.glimmer(), () -> upsideDownWorld, configuration::islandWorlds);
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

    /** What the Upside Down makes of the creatures born in it, while StrangerRealms is enabled. */
    public UpsideDownSpawns spawns() {
        return spawns;
    }

    /** The glimmer between the land and the Upside Down. */
    public com.uxplima.uxmskyblock.bukkit.stranger.Glimmer glimmer() {
        return glimmer;
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
