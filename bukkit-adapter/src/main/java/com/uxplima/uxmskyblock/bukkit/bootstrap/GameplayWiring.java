package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
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
import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandChatDeliveryAdapter;
import com.uxplima.uxmskyblock.bukkit.chat.BukkitIslandOnlineMemberProvider;
import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitIslandArchivalAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitIslandRecycleAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitPlayerActivityProvider;
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
import com.uxplima.uxmskyblock.bukkit.reward.CosmeticRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.ExternalVaultRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.ItemRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.PermissionRewardDeliveryHandler;
import com.uxplima.uxmskyblock.bukkit.reward.SqlCurrencyRewardDeliveryHandler;
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
import com.uxplima.uxmskyblock.core.application.reward.RewardClaimCoordinator;
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
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
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
    private final IslandBankService bankService;
    private final IslandLeaderboardService leaderboardService;
    private final IslandSeasonService seasonService;
    private final IslandSocialService socialService;
    private final IslandAllianceService allianceService;
    private final DynamicPricingEngine dynamicPricingEngine;
    private final TemporaryAccessService temporaryAccessService;
    private final BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;
    private final IslandAdminFreezeService freezeService;
    private final IslandProtectionListener protectionListener;
    private final BukkitBiomeAdapter biomeAdapter;
    private final IslandMissionService missionService;
    private final @Nullable IslandMissionsMenu missionsMenu;
    private final @Nullable IslandMissionListener missionListener;
    private final WorldBorderPacketAdapter worldBorderAdapter;
    private final IslandBoundaryService boundaryService;
    private final @Nullable IslandBoundaryListener boundaryListener;
    private final NbtIslandBackupAdapter islandBackupAdapter;
    private final FoliaIslandVoidingAdapter voidingAdapter;
    private final IslandRecycleService recycleService;
    private final @Nullable IslandResetConfirmationMenu resetConfirmationMenu;
    private final @Nullable FoliaIslandChunkScanner chunkScanner;
    private final IslandWorthService worthService;
    private final @Nullable IslandWorthListener worthListener;
    private final IslandDimensionService dimensionService;
    private final @Nullable IslandDimensionListener dimensionListener;
    private final IslandLimitService limitService;
    private final @Nullable IslandLimitListener limitListener;
    private final @Nullable IslandLimitReconciler limitReconciler;
    private final IslandAntiAbuseService antiAbuseService;
    private final @Nullable IslandAntiAbuseListener antiAbuseListener;
    private final IslandBoosterService boosterService;
    private final @Nullable IslandBoosterListener boosterListener;
    private final @Nullable IslandBoosterMenu boosterMenu;
    private final IslandBankruptcyService bankruptcyService;
    private final @Nullable IslandBankruptcyListener bankruptcyListener;
    private final IslandNameService islandNameService;
    private final KineticWardService kineticWardService;
    private final KineticWardListener kineticWardListener;
    private final ObsidianRecoveryListener obsidianRecoveryListener;
    private final VoidProtectionListener voidProtectionListener;
    private final CategoricalInteractablesListener categoricalInteractablesListener;
    private final IslandRedstoneOptimizationListener redstoneOptimizationListener;
    private final AsyncStructureSuppressionListener structureSuppressionListener;
    private final RewardInboxService rewardInboxService;
    private final IslandUpgradeService upgradeService;
    private final @Nullable OreGeneratorListener oreGeneratorListener;
    private final UpgradesModule upgradesModule;
    private final SafeTeleportEngine safeTeleportEngine;
    private final IslandWarpService warpService;
    private final IslandVaultService vaultService;
    private final IslandChatService chatService;
    private final @Nullable IslandChatListener chatListener;
    private final IslandInactivityService inactivityService;
    private final WorldDimensionSnapshotPort worldDimensionSnapshotPort;
    private final ObjectStoragePort objectStoragePort;
    private final BackupService backupService;
    private final IslandRestoreService islandRestoreService;

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
        this.allianceService = Objects.requireNonNull(allianceService, "allianceService must not be null");
        this.temporaryAccessService =
                Objects.requireNonNull(temporaryAccessService, "temporaryAccessService must not be null");
        this.visitorEvictionAdapter =
                Objects.requireNonNull(visitorEvictionAdapter, "visitorEvictionAdapter must not be null");
        this.freezeService = Objects.requireNonNull(freezeService, "freezeService must not be null");
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
        this.bankService = new IslandBankService(
                persistence.islandBankPort(),
                persistence.islandStoragePort(),
                persistence.islandAuthorityPort(),
                persistence.outboxPort());
        this.leaderboardService = new IslandLeaderboardService(persistence.islandLeaderboardPort());
        this.seasonService = new IslandSeasonService(
                persistence.islandSeasonStoragePort(),
                persistence.islandLeaderboardPort(),
                persistence.islandStoragePort());
        this.socialService = new IslandSocialService(
                persistence.islandSocialStoragePort(),
                RatingPolicy.standardFiveStar(),
                persistence.islandStoragePort(),
                config.socialConfig().minDwellTime(),
                config.socialConfig().priorWeight(),
                config.socialConfig().priorMean(),
                config.socialConfig().maxPinned(),
                config.socialConfig().maxMessageLength());
        this.upgradeService = new IslandUpgradeService(
                persistence.islandUpgradeStoragePort(), config.upgradesConfig().definitions());

        this.dynamicPricingEngine = new DynamicPricingEngine(config.shopConfig().dampingFactor());

        this.safeTeleportEngine = new SafeTeleportEngine(config.warpConfig().searchRadius());
        this.warpService = new IslandWarpService(
                persistence.islandWarpStoragePort(),
                safeTeleportEngine,
                this.upgradeService,
                config.warpConfig().baseWarpLimit(),
                (targetIslandId, visitorProfileId) -> {
                    if (!config.allianceConfig().privilegedVisitAccess()) {
                        return false;
                    }
                    return persistence
                            .islandStoragePort()
                            .findIslandIdByProfileId(visitorProfileId)
                            .map(visitorIslandId -> allianceService.canPrivilegedVisit(visitorIslandId, targetIslandId))
                            .orElse(false);
                });

        this.vaultService = new IslandVaultService(
                persistence.islandVaultStoragePort(),
                this.upgradeService,
                config.vaultConfig().basePages(),
                config.vaultConfig().maxPages(),
                config.vaultConfig().leaseDuration());

        IslandChatTransportPort actualChatTransport =
                Objects.requireNonNull(chatTransport, "chatTransport must not be null");
        BukkitIslandChatDeliveryAdapter chatDelivery = new BukkitIslandChatDeliveryAdapter(config.chatConfig());
        BukkitIslandOnlineMemberProvider chatMemberProvider = new BukkitIslandOnlineMemberProvider(
                persistence.islandStoragePort(), authority.activeProfileProvider());
        this.chatService = new IslandChatService(
                persistence.islandStoragePort(),
                actualChatTransport,
                chatDelivery,
                chatMemberProvider,
                config.chatConfig().rateLimitMessagesPerSecond());
        this.chatListener = config.moduleSettings().isModuleEnabled("chat")
                ? new IslandChatListener(chatService, authority.activeProfileProvider())
                : null;

        this.voidingAdapter = new FoliaIslandVoidingAdapter(scheduler, this.backpressureController);
        BukkitIslandArchivalAdapter archivalAdapter =
                new BukkitIslandArchivalAdapter(protectionListener, visitorEvictionAdapter);
        BukkitIslandRecycleAdapter recycleAdapter = new BukkitIslandRecycleAdapter(
                persistence.islandStoragePort(),
                persistence.worldGridAllocationPort(),
                persistence.spiralSlotPoolPort(),
                voidingAdapter,
                protectionListener,
                visitorEvictionAdapter);

        BukkitPlayerActivityProvider activityProvider = new BukkitPlayerActivityProvider();
        this.inactivityService = new IslandInactivityService(
                persistence.islandStoragePort(),
                activityProvider,
                config.inactivityConfig().toPolicy(),
                archivalAdapter,
                recycleAdapter,
                persistence.outboxPort());

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

        this.worldDimensionSnapshotPort = new com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter(
                plugin, persistence.islandStoragePort(), scheduler, config.dimensionConfig());
        this.islandBackupAdapter = new NbtIslandBackupAdapter(
                plugin.getDataFolder(), this.worldDimensionSnapshotPort, persistence.gameModeHierarchyStoragePort());
        this.recycleService = new IslandRecycleService(
                persistence.islandStoragePort(),
                persistence.worldGridAllocationPort(),
                persistence.spiralSlotPoolPort(),
                voidingAdapter,
                islandBackupAdapter,
                persistence.outboxPort(),
                persistence.islandRecycleOperationPort());
        this.resetConfirmationMenu = new IslandResetConfirmationMenu(
                recycleService, persistence.islandStoragePort(), authority.sessionCoordinator(), scheduler);

        this.objectStoragePort = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.backupService = new BackupService(persistence.backupCatalogPort(), this.objectStoragePort);
        this.islandRestoreService = new IslandRestoreService(
                persistence.backupCatalogPort(),
                this.objectStoragePort,
                persistence.rootRelationalSnapshotPort(),
                this.worldDimensionSnapshotPort);

        boolean worthEnabled = config.moduleSettings().isModuleEnabled("worth");
        this.chunkScanner = worthEnabled
                ? new FoliaIslandChunkScanner(
                        this.scheduler,
                        config.levelConfig().blockWeights().keySet(),
                        config.levelConfig().spawnerWeights().keySet())
                : null;
        MaterialValuationIndex valuationIndex = new MaterialValuationIndex();
        config.levelConfig().blockWeights().forEach(valuationIndex::setWeight);
        config.levelConfig().basePricesMinorUnits().forEach(valuationIndex::setPrice);
        this.worthService = new IslandWorthService(
                valuationIndex,
                config.levelConfig().spawnerWeights(),
                config.levelConfig().defaultSpawnerWeight(),
                config.levelConfig().questWeight(),
                config.levelConfig().pointsPerLevel(),
                config.levelConfig().bankMinorUnitsPerPoint(),
                config.levelConfig().dampingFactor(),
                persistence.islandLeaderboardPort(),
                this.chunkScanner);
        this.worthListener = worthEnabled ? new IslandWorthListener(this.worthService, this.protectionListener) : null;

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

        this.antiAbuseService = new IslandAntiAbuseService(
                persistence.antiAbuseStoragePort(),
                config.antiAbuseConfig().purgeInventoryOnReset(),
                config.antiAbuseConfig().quarantineDuration(),
                config.antiAbuseConfig().resetCooldown(),
                config.antiAbuseConfig().maxResetsPerDay(),
                config.antiAbuseConfig().resetWindowDuration(),
                config.antiAbuseConfig().coopJoinCooldown(),
                java.time.Clock.systemUTC());
        this.antiAbuseListener = config.moduleSettings().isModuleEnabled("anti-abuse")
                ? new IslandAntiAbuseListener(
                        persistence.islandStoragePort(), this.antiAbuseService, config.antiAbuseConfig())
                : null;

        boolean boostersEnabled = config.moduleSettings().isModuleEnabled("boosters");
        this.boosterService = new IslandBoosterService(
                persistence.islandBoosterStoragePort(),
                config.boosterConfig()::policy,
                config.boosterConfig().pauseWhenEmpty());
        this.boosterListener = boostersEnabled
                ? new IslandBoosterListener(
                        persistence.islandStoragePort(),
                        this.boosterService,
                        config.boosterConfig(),
                        authority.sessionCoordinator(),
                        this.scheduler)
                : null;
        this.boosterMenu = boostersEnabled
                ? new IslandBoosterMenu(
                        persistence.islandStoragePort(),
                        this.boosterService,
                        config.boosterConfig(),
                        authority.sessionCoordinator(),
                        this.scheduler)
                : null;

        this.bankruptcyService = new IslandBankruptcyService(
                persistence.islandBankruptcyStoragePort(),
                persistence.islandBankPort(),
                persistence.islandAuthorityPort(),
                config.bankConfig()::upkeepPolicy);
        this.bankruptcyListener = config.moduleSettings().isModuleEnabled("bank-upkeep")
                ? new IslandBankruptcyListener(
                        this.bankruptcyService,
                        this.protectionListener,
                        persistence.islandStoragePort(),
                        authority.sessionCoordinator(),
                        this.scheduler)
                : null;

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
                this.temporaryAccessService);
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

        ItemRewardDeliveryHandler itemDeliveryHandler = new ItemRewardDeliveryHandler(
                authority.sessionCoordinator(),
                persistence.mutationJournalPort(),
                config.nodeConfig().nodeId());

        SqlCurrencyRewardDeliveryHandler currencyDeliveryHandler = new SqlCurrencyRewardDeliveryHandler(
                persistence.islandStoragePort(),
                this.bankService,
                config.nodeConfig().nodeId(),
                persistence.profileSwitchPort());

        ExternalVaultRewardDeliveryHandler vaultDeliveryHandler = new ExternalVaultRewardDeliveryHandler(
                economyBridgeSupplier,
                persistence.economySagaPort(),
                persistence.profileSwitchPort(),
                persistence.islandStoragePort());

        CosmeticRewardDeliveryHandler cosmeticDeliveryHandler =
                new CosmeticRewardDeliveryHandler(persistence.profileCosmeticStoragePort());

        PermissionRewardDeliveryHandler permDeliveryHandler = new PermissionRewardDeliveryHandler(
                PermissionRewardDeliveryHandler::fromVault,
                persistence.profileSwitchPort(),
                authority.sessionCoordinator());

        RewardClaimCoordinator rewardClaimCoordinator = new RewardClaimCoordinator(
                persistence.rewardStoragePort(),
                List.of(
                        itemDeliveryHandler,
                        currencyDeliveryHandler,
                        vaultDeliveryHandler,
                        cosmeticDeliveryHandler,
                        permDeliveryHandler));

        this.rewardInboxService = new RewardInboxService(persistence.rewardStoragePort(), rewardClaimCoordinator);

        boolean upgradesEnabled = config.moduleSettings().isModuleEnabled("upgrades");
        this.oreGeneratorListener = upgradesEnabled
                ? new OreGeneratorListener(
                        this.upgradeService, config.generatorsConfig(), this.protectionListener.spatialIndex())
                : null;
        this.upgradesModule = new UpgradesModule(
                this.upgradeService,
                config.upgradesConfig(),
                config.generatorsConfig(),
                this.oreGeneratorListener,
                plugin);
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

    public IslandLocationService locationService() {
        return locationService;
    }

    public IslandBankService bankService() {
        return bankService;
    }

    public IslandLeaderboardService leaderboardService() {
        return leaderboardService;
    }

    public IslandSeasonService seasonService() {
        return seasonService;
    }

    public IslandSocialService socialService() {
        return socialService;
    }

    public IslandAllianceService allianceService() {
        return allianceService;
    }

    public DynamicPricingEngine dynamicPricingEngine() {
        return dynamicPricingEngine;
    }

    public TemporaryAccessService temporaryAccessService() {
        return temporaryAccessService;
    }

    public BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter() {
        return visitorEvictionAdapter;
    }

    public IslandAdminFreezeService freezeService() {
        return freezeService;
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
        return voidingAdapter;
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return islandBackupAdapter;
    }

    public IslandRecycleService recycleService() {
        return recycleService;
    }

    public IslandResetConfirmationMenu resetConfirmationMenu() {
        return resetConfirmationMenu;
    }

    public @Nullable FoliaIslandChunkScanner chunkScanner() {
        return chunkScanner;
    }

    public IslandWorthService worthService() {
        return worthService;
    }

    public @Nullable IslandWorthListener worthListener() {
        return worthListener;
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
        return antiAbuseService;
    }

    public @Nullable IslandAntiAbuseListener antiAbuseListener() {
        return antiAbuseListener;
    }

    public IslandBoosterService boosterService() {
        return boosterService;
    }

    public @Nullable IslandBoosterListener boosterListener() {
        return boosterListener;
    }

    public @Nullable IslandBoosterMenu boosterMenu() {
        return boosterMenu;
    }

    public IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public @Nullable IslandBankruptcyListener bankruptcyListener() {
        return bankruptcyListener;
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
        return rewardInboxService;
    }

    public IslandUpgradeService upgradeService() {
        return upgradeService;
    }

    public @Nullable OreGeneratorListener oreGeneratorListener() {
        return oreGeneratorListener;
    }

    public UpgradesModule upgradesModule() {
        return upgradesModule;
    }

    public SafeTeleportEngine safeTeleportEngine() {
        return safeTeleportEngine;
    }

    public IslandWarpService warpService() {
        return warpService;
    }

    public IslandVaultService vaultService() {
        return vaultService;
    }

    public IslandChatService chatService() {
        return chatService;
    }

    public @Nullable IslandChatListener chatListener() {
        return chatListener;
    }

    public IslandInactivityService inactivityService() {
        return inactivityService;
    }

    public WorldDimensionSnapshotPort worldDimensionSnapshotPort() {
        return worldDimensionSnapshotPort;
    }

    public ObjectStoragePort objectStoragePort() {
        return objectStoragePort;
    }

    public BackupService backupService() {
        return backupService;
    }

    public IslandRestoreService islandRestoreService() {
        return islandRestoreService;
    }
}
