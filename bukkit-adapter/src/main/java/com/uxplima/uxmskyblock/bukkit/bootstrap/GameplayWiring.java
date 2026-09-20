package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.bank.IslandBankruptcyListener;
import com.uxplima.uxmskyblock.bukkit.biome.BukkitBiomeAdapter;
import com.uxplima.uxmskyblock.bukkit.booster.IslandBoosterListener;
import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.bukkit.boundary.WorldBorderPacketAdapter;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitListener;
import com.uxplima.uxmskyblock.bukkit.limit.IslandLimitReconciler;
import com.uxplima.uxmskyblock.bukkit.listener.IslandChatListener;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.mission.IslandMissionListener;
import com.uxplima.uxmskyblock.bukkit.module.builtin.UpgradesModule;
import com.uxplima.uxmskyblock.bukkit.performance.IslandRedstoneOptimizationListener;
import com.uxplima.uxmskyblock.bukkit.protection.CategoricalInteractablesListener;
import com.uxplima.uxmskyblock.bukkit.protection.ObsidianRecoveryListener;
import com.uxplima.uxmskyblock.bukkit.protection.VoidProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.upgrade.OreGeneratorListener;
import com.uxplima.uxmskyblock.bukkit.ward.KineticWardListener;
import com.uxplima.uxmskyblock.bukkit.world.AsyncStructureSuppressionListener;
import com.uxplima.uxmskyblock.bukkit.worth.FoliaIslandChunkScanner;
import com.uxplima.uxmskyblock.bukkit.worth.IslandWorthListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.chat.LocalIslandChatTransportAdapter;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import org.jspecify.annotations.Nullable;

/**
 * Encapsulates core gameplay domain services, region schedulers, boundary management,
 * protection listeners, physical item delivery pipelines, and island upgrade loops.
 */
public final class GameplayWiring {

    private final SchedulerPort scheduler;
    private final AdaptiveBackpressureController backpressureController;
    private final IslandAccessService accessService;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final SpiralGridCoordinateAllocator coordinateAllocator;
    private final SpiralWorldGridService gridService;
    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService locationService;
    private final IslandLeaderboardService leaderboardService;
    private final IslandSeasonService seasonService;
    private final BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;
    private final IslandProtectionListener protectionListener;
    private final BukkitBiomeAdapter biomeAdapter;
    private final IslandMissionService missionService;
    private final @Nullable IslandMissionsMenu missionsMenu;
    private final @Nullable IslandMissionListener missionListener;
    private final WorldBorderPacketAdapter worldBorderAdapter;
    private final IslandBoundaryService boundaryService;
    private final @Nullable IslandBoundaryListener boundaryListener;
    private final IslandDimensionService dimensionService;
    private final @Nullable IslandDimensionListener dimensionListener;
    private final IslandLimitService limitService;
    private final @Nullable IslandLimitListener limitListener;
    private final @Nullable IslandLimitReconciler limitReconciler;
    private final IslandNameService islandNameService;
    private final KineticWardService kineticWardService;
    private final KineticWardListener kineticWardListener;
    private final ObsidianRecoveryListener obsidianRecoveryListener;
    private final VoidProtectionListener voidProtectionListener;
    private final CategoricalInteractablesListener categoricalInteractablesListener;
    private final IslandRedstoneOptimizationListener redstoneOptimizationListener;
    private final AsyncStructureSuppressionListener structureSuppressionListener;

    private final AdminWiring adminWiring;
    private final EconomicWiring economicWiring;
    private final SocialWiring socialWiring;

    public GameplayWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            IslandAccessService accessService,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            Supplier<SkyblockEconomyBridge> economyBridgeSupplier) {
        this(
                plugin,
                config,
                persistence,
                authority,
                protectionListener,
                accessService,
                allianceService,
                temporaryAccessService,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                backpressureController,
                economyBridgeSupplier,
                new LocalIslandChatTransportAdapter(),
                new LocalFilesystemStorageAdapter(
                        plugin.getDataFolder().toPath().resolve("backups")));
    }

    public GameplayWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            IslandAccessService accessService,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            Supplier<SkyblockEconomyBridge> economyBridgeSupplier,
            IslandChatTransportPort chatTransport) {
        this(
                plugin,
                config,
                persistence,
                authority,
                protectionListener,
                accessService,
                allianceService,
                temporaryAccessService,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                backpressureController,
                economyBridgeSupplier,
                chatTransport,
                new LocalFilesystemStorageAdapter(
                        plugin.getDataFolder().toPath().resolve("backups")));
    }

    public GameplayWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            IslandAccessService accessService,
            IslandAllianceService allianceService,
            TemporaryAccessService temporaryAccessService,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            Supplier<SkyblockEconomyBridge> economyBridgeSupplier,
            IslandChatTransportPort chatTransport,
            ObjectStoragePort objectStorage) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.backpressureController =
                Objects.requireNonNull(backpressureController, "backpressureController must not be null");
        this.accessService = Objects.requireNonNull(accessService, "accessService must not be null");
        this.visitorEvictionAdapter =
                Objects.requireNonNull(visitorEvictionAdapter, "visitorEvictionAdapter must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");

        String worldName = config.nodeConfig().worldName();

        this.presetCatalog = new StarterPresetCatalog();
        this.schematicEngine = new StarterSchematicEngine(this.backpressureController);
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
        this.economicWiring = new EconomicWiring(
                plugin, config, persistence, authority, protectionListener, scheduler, economyBridgeSupplier);

        this.seasonService = new IslandSeasonService(
                persistence.islandSeasonStoragePort(),
                persistence.islandLeaderboardPort(),
                persistence.islandStoragePort(),
                this.economicWiring.rewardInboxService());

        this.socialWiring = new SocialWiring(
                config,
                persistence,
                authority,
                allianceService,
                temporaryAccessService,
                this.economicWiring.upgradeService(),
                chatTransport);

        this.adminWiring = new AdminWiring(
                plugin,
                config,
                persistence,
                authority,
                protectionListener,
                visitorEvictionAdapter,
                freezeService,
                scheduler,
                this.backpressureController,
                objectStorage);

        this.biomeAdapter = new BukkitBiomeAdapter(persistence.islandStoragePort(), scheduler, worldName);

        this.missionService = new IslandMissionService(persistence.islandMissionStoragePort());
        if (config.moduleSettings().isModuleEnabled("missions")) {
            this.missionService.registerMissions(config.missionConfig().missions());
            this.missionsMenu = new IslandMissionsMenu(
                    missionService, persistence.islandStoragePort(), authority.sessionCoordinator(), scheduler);
            this.missionListener = new IslandMissionListener(
                    missionService, persistence.islandStoragePort(), authority.sessionCoordinator(), scheduler);
        } else {
            this.missionsMenu = null;
            this.missionListener = null;
        }

        this.worldBorderAdapter = new WorldBorderPacketAdapter(scheduler);
        this.boundaryService = new IslandBoundaryService(worldBorderAdapter);
        if (config.moduleSettings().isModuleEnabled("boundary")) {
            this.boundaryListener = new IslandBoundaryListener(boundaryService, protectionListener, scheduler);
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
                        this.scheduler,
                        p -> authority.sessionCoordinator().activeProfile(p.getUniqueId()),
                        worldName)
                : null;

        boolean limitsEnabled = config.moduleSettings().isModuleEnabled("limits");
        this.limitService = new IslandLimitService(
                persistence.islandUpgradeStoragePort(), config.limitConfig().quotas());
        this.limitListener = limitsEnabled
                ? new IslandLimitListener(
                        this.limitService,
                        this.protectionListener,
                        config.limitConfig().bypassPermission())
                : null;
        this.limitReconciler = limitsEnabled ? new IslandLimitReconciler(this.scheduler, this.limitService) : null;

        this.islandNameService = new IslandNameService(
                persistence.islandNameStoragePort(),
                persistence.islandStoragePort(),
                this.accessService,
                persistence.outboxPort());

        this.kineticWardService = new KineticWardService(
                config.protectionConfig().kineticWardRadius(),
                config.protectionConfig().kineticWardForce(),
                config.protectionConfig().kineticWardVerticalLift());
        this.kineticWardListener = new KineticWardListener(config.protectionConfig(), this.kineticWardService);

        this.obsidianRecoveryListener = new ObsidianRecoveryListener(config.protectionConfig());
        this.voidProtectionListener = new VoidProtectionListener(
                config.protectionConfig(), config.settingsConfig(), this.protectionListener::findIslandAt);

        this.categoricalInteractablesListener = new CategoricalInteractablesListener(
                config.interactablesConfig(),
                this.protectionListener::findIslandAt,
                uuid -> authority
                        .sessionCoordinator()
                        .activeProfile(uuid.value())
                        .orElse(null),
                temporaryAccessService);
        this.categoricalInteractablesListener.setNodeIdentitySupplier(authority::nodeProcessIdentity);
        this.categoricalInteractablesListener.setSessionRecordProvider(uuid -> {
            PlayerSessionCoordinator.ActiveSession session =
                    authority.sessionCoordinator().getActiveSession(uuid.value());
            if (session == null || session.isFenced()) {
                return Optional.empty();
            }
            return persistence.sessionAuthorityPort().findSession(uuid);
        });

        this.redstoneOptimizationListener =
                new IslandRedstoneOptimizationListener(config.settingsConfig(), this.protectionListener::findIslandAt);
        this.structureSuppressionListener = new AsyncStructureSuppressionListener(config.worldConfig());
    }

    public SchedulerPort scheduler() {
        return scheduler;
    }

    public AdaptiveBackpressureController backpressureController() {
        return backpressureController;
    }

    public IslandAccessService accessService() {
        return accessService;
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

    public AdminWiring adminWiring() {
        return adminWiring;
    }

    public EconomicWiring economicWiring() {
        return economicWiring;
    }

    public SocialWiring socialWiring() {
        return socialWiring;
    }

    public IslandLocationService locationService() {
        return locationService;
    }

    public IslandBankService bankService() {
        return economicWiring.bankService();
    }

    public IslandLeaderboardService leaderboardService() {
        return leaderboardService;
    }

    public IslandSeasonService seasonService() {
        return seasonService;
    }

    public IslandSocialService socialService() {
        return socialWiring.socialService();
    }

    public IslandAllianceService allianceService() {
        return socialWiring.allianceService();
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return economicWiring.dynamicPricingEngine();
    }

    public TemporaryAccessService temporaryAccessService() {
        return socialWiring.temporaryAccessService();
    }

    public BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter() {
        return visitorEvictionAdapter;
    }

    public IslandAdminFreezeService freezeService() {
        return adminWiring.freezeService();
    }

    public IslandProtectionListener protectionListener() {
        return protectionListener;
    }

    public BukkitBiomeAdapter biomeAdapter() {
        return biomeAdapter;
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

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return worldBorderAdapter;
    }

    public IslandBoundaryService boundaryService() {
        return boundaryService;
    }

    public @Nullable IslandBoundaryListener boundaryListener() {
        return boundaryListener;
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return adminWiring.voidingAdapter();
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return adminWiring.islandBackupAdapter();
    }

    public IslandRecycleService recycleService() {
        return adminWiring.recycleService();
    }

    public @Nullable IslandResetConfirmationMenu resetConfirmationMenu() {
        return adminWiring.resetConfirmationMenu();
    }

    public @Nullable FoliaIslandChunkScanner chunkScanner() {
        return economicWiring.chunkScanner();
    }

    public IslandWorthService worthService() {
        return economicWiring.worthService();
    }

    public @Nullable IslandWorthListener worthListener() {
        return economicWiring.worthListener();
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

    public IslandAntiAbuseService antiAbuseService() {
        return adminWiring.antiAbuseService();
    }

    public @Nullable IslandAntiAbuseListener antiAbuseListener() {
        return adminWiring.antiAbuseListener();
    }

    public IslandBoosterService boosterService() {
        return economicWiring.boosterService();
    }

    public @Nullable IslandBoosterListener boosterListener() {
        return economicWiring.boosterListener();
    }

    public @Nullable IslandBoosterMenu boosterMenu() {
        return economicWiring.boosterMenu();
    }

    public IslandBankruptcyService bankruptcyService() {
        return economicWiring.bankruptcyService();
    }

    public @Nullable IslandBankruptcyListener bankruptcyListener() {
        return economicWiring.bankruptcyListener();
    }

    public IslandNameService islandNameService() {
        return islandNameService;
    }

    public KineticWardService kineticWardService() {
        return kineticWardService;
    }

    public KineticWardListener kineticWardListener() {
        return kineticWardListener;
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return obsidianRecoveryListener;
    }

    public VoidProtectionListener voidProtectionListener() {
        return voidProtectionListener;
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return categoricalInteractablesListener;
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return redstoneOptimizationListener;
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return structureSuppressionListener;
    }

    public RewardInboxService rewardInboxService() {
        return economicWiring.rewardInboxService();
    }

    public IslandUpgradeService upgradeService() {
        return economicWiring.upgradeService();
    }

    public @Nullable OreGeneratorListener oreGeneratorListener() {
        return economicWiring.oreGeneratorListener();
    }

    public UpgradesModule upgradesModule() {
        return economicWiring.upgradesModule();
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return socialWiring.safeTeleportEngine();
    }

    public IslandWarpService warpService() {
        return socialWiring.warpService();
    }

    public IslandVaultService vaultService() {
        return socialWiring.vaultService();
    }

    public IslandChatService chatService() {
        return socialWiring.chatService();
    }

    public @Nullable IslandChatListener chatListener() {
        return socialWiring.chatListener();
    }

    public IslandInactivityService inactivityService() {
        return adminWiring.inactivityService();
    }

    public WorldDimensionSnapshotPort worldDimensionSnapshotPort() {
        return adminWiring.worldDimensionSnapshotPort();
    }

    public ObjectStoragePort objectStoragePort() {
        return adminWiring.objectStoragePort();
    }

    public BackupService backupService() {
        return adminWiring.backupService();
    }

    public IslandRestoreService islandRestoreService() {
        return adminWiring.islandRestoreService();
    }
}
