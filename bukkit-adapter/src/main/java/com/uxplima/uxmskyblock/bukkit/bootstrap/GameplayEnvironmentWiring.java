package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.boundary.WorldBorderPacketAdapter;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitReconciler;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates environment services: biome modification, world borders/boundaries,
 * dimension mapping, and island quotas/limits.
 */
public final class GameplayEnvironmentWiring {

    private final BukkitBiomeAdapter biomeAdapter;
    private final WorldBorderPacketAdapter worldBorderAdapter;
    private final IslandBoundaryService boundaryService;
    private final @Nullable IslandBoundaryListener boundaryListener;
    private final IslandDimensionService dimensionService;
    private final @Nullable IslandDimensionListener dimensionListener;
    private final IslandLimitService limitService;
    private final @Nullable IslandLimitListener limitListener;
    private final @Nullable IslandLimitReconciler limitReconciler;

    public GameplayEnvironmentWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            IslandLocationService locationService,
            StarterSchematicEngine schematicEngine,
            SchedulerPort scheduler,
            String worldName,
            IslandUpgradeService upgradeService) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(persistence, "persistence");
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(protectionListener, "protectionListener");
        Objects.requireNonNull(locationService, "locationService");
        Objects.requireNonNull(schematicEngine, "schematicEngine");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(worldName, "worldName");

        this.biomeAdapter = new BukkitBiomeAdapter(persistence.islandStoragePort(), scheduler, worldName);

        this.worldBorderAdapter = new WorldBorderPacketAdapter(scheduler);
        this.boundaryService = new IslandBoundaryService(worldBorderAdapter);
        if (config.moduleSettings().isModuleEnabled("boundary")) {
            this.boundaryListener =
                    new IslandBoundaryListener(boundaryService, protectionListener, scheduler, config.messages());
            this.boundaryListener.setStopBorderCrossing(config.settingsConfig().stopBorderCrossing());
        } else {
            this.boundaryListener = null;
        }

        this.dimensionService = new IslandDimensionService(
                persistence.islandUpgradeStoragePort(),
                config.dimensionConfig().mappings(),
                persistence.islandDimensionStoragePort());
        this.dimensionListener = config.moduleSettings().isModuleEnabled("dimensions")
                ? new IslandDimensionListener(
                        this.dimensionService,
                        locationService,
                        schematicEngine,
                        scheduler,
                        p -> authority.sessionCoordinator().activeProfile(p.getUniqueId()),
                        worldName,
                        config.messages())
                : null;

        boolean limitsEnabled = config.moduleSettings().isModuleEnabled("limits");
        // The limit is asked on every block a player places. The upgrade service answers the tier
        // through its own cache, so a player building runs one query for their island rather than
        // one for every block they put down.
        this.limitService = new IslandLimitService(
                upgradeService::getCurrentTier, config.limitConfig().quotas());
        this.limitListener = limitsEnabled
                ? new IslandLimitListener(
                        this.limitService,
                        protectionListener,
                        config.limitConfig().bypassPermission(),
                        config.messages())
                : null;
        this.limitReconciler = limitsEnabled ? new IslandLimitReconciler(scheduler, this.limitService) : null;
        if (this.limitListener != null) {
            // Nothing called the scan before, so every island started each boot at zero and could
            // place its whole allowance again.
            this.limitListener.useReconciler(this.limitReconciler);
        }
    }

    public BukkitBiomeAdapter biomeAdapter() {
        return biomeAdapter;
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return worldBorderAdapter;
    }

    public IslandBoundaryService boundaryService() {
        return boundaryService;
    }

    public @Nullable IslandBoundaryListener boundaryListener() {
        return boundaryListener;
    }

    public IslandDimensionService dimensionService() {
        return dimensionService;
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return dimensionListener;
    }

    public IslandLimitService limitService() {
        return limitService;
    }

    public @Nullable IslandLimitListener limitListener() {
        return limitListener;
    }

    public @Nullable IslandLimitReconciler limitReconciler() {
        return limitReconciler;
    }
}
