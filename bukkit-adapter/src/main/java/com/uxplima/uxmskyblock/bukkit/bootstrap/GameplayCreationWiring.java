package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates island creation, schematic pasting, coordinate allocation, mission, and naming subsystems.
 */
public final class GameplayCreationWiring {

    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final SpiralGridCoordinateAllocator coordinateAllocator;
    private final SpiralWorldGridService gridService;
    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService locationService;
    private final IslandLeaderboardService leaderboardService;
    private final IslandSeasonService seasonService;
    private final IslandMissionService missionService;
    private final @Nullable IslandMissionsMenu missionsMenu;
    private final @Nullable IslandMissionListener missionListener;
    private final IslandNameService islandNameService;

    public GameplayCreationWiring(
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            IslandAccessService accessService,
            RewardInboxService rewardInboxService) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(persistence, "persistence must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        Objects.requireNonNull(backpressureController, "backpressureController must not be null");
        Objects.requireNonNull(accessService, "accessService must not be null");
        Objects.requireNonNull(rewardInboxService, "rewardInboxService must not be null");

        this.presetCatalog = new StarterPresetCatalog();
        this.schematicEngine = new StarterSchematicEngine(backpressureController);
        this.coordinateAllocator = new SpiralGridCoordinateAllocator();
        this.gridService = new SpiralWorldGridService(coordinateAllocator);

        this.createIslandUseCase = new CreateIslandUseCase(
                persistence.islandStoragePort(),
                persistence.islandAuthorityPort(),
                persistence.islandBankPort(),
                presetCatalog,
                gridService,
                persistence.worldGridAllocationPort(),
                persistence.outboxPort());
        this.locationService = new IslandLocationService(persistence.islandStoragePort());
        this.leaderboardService = new IslandLeaderboardService(persistence.islandLeaderboardPort());

        this.seasonService = new IslandSeasonService(
                persistence.islandSeasonStoragePort(),
                persistence.islandLeaderboardPort(),
                persistence.islandStoragePort(),
                rewardInboxService);

        this.missionService = new IslandMissionService(persistence.islandMissionStoragePort());
        if (config.moduleSettings().isModuleEnabled("missions")) {
            this.missionService.registerMissions(config.missionConfig().missions());
            this.missionsMenu = new IslandMissionsMenu(
                    missionService, persistence.islandStoragePort(), authority.sessionCoordinator(), scheduler);
            this.missionListener = new IslandMissionListener(
                    missionService,
                    persistence.islandStoragePort(),
                    authority.sessionCoordinator(),
                    scheduler,
                    config.messages());
        } else {
            this.missionsMenu = null;
            this.missionListener = null;
        }

        this.islandNameService = new IslandNameService(
                persistence.islandNameStoragePort(),
                persistence.islandStoragePort(),
                accessService,
                persistence.outboxPort());
    }

    public StarterPresetCatalog presetCatalog() {
        return presetCatalog;
    }

    public StarterSchematicEngine schematicEngine() {
        return schematicEngine;
    }

    public SpiralGridCoordinateAllocator coordinateAllocator() {
        return coordinateAllocator;
    }

    public SpiralWorldGridService gridService() {
        return gridService;
    }

    public CreateIslandUseCase createIslandUseCase() {
        return createIslandUseCase;
    }

    public IslandLocationService locationService() {
        return locationService;
    }

    public IslandLeaderboardService leaderboardService() {
        return leaderboardService;
    }

    public IslandSeasonService seasonService() {
        return seasonService;
    }

    public IslandMissionService missionService() {
        return missionService;
    }

    public @Nullable IslandMissionsMenu missionsMenu() {
        return missionsMenu;
    }

    public @Nullable IslandMissionListener missionListener() {
        return missionListener;
    }

    public IslandNameService islandNameService() {
        return islandNameService;
    }
}
