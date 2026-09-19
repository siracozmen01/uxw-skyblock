package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.time.Instant;
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
import com.uxplima.uxmskyblock.core.application.social.IslandSocialService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.application.warp.SafeTeleportEngine;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.level.MaterialValuationIndex;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

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
    private final IslandMissionsMenu missionsMenu;
    private final IslandMissionListener missionListener;
    private final WorldBorderPacketAdapter worldBorderAdapter;
    private final IslandBoundaryService boundaryService;
    private final IslandBoundaryListener boundaryListener;
    private final FoliaIslandVoidingAdapter voidingAdapter;
    private final NbtIslandBackupAdapter islandBackupAdapter;
    private final IslandRecycleService recycleService;
    private final IslandResetConfirmationMenu resetConfirmationMenu;
    private final FoliaIslandChunkScanner chunkScanner;
    private final IslandWorthService worthService;
    private final IslandWorthListener worthListener;
    private final IslandDimensionService dimensionService;
    private final IslandDimensionListener dimensionListener;
    private final IslandLimitService limitService;
    private final IslandLimitListener limitListener;
    private final IslandLimitReconciler limitReconciler;
    private final IslandAntiAbuseService antiAbuseService;
    private final IslandAntiAbuseListener antiAbuseListener;
    private final IslandBoosterService boosterService;
    private final IslandBoosterListener boosterListener;
    private final IslandBoosterMenu boosterMenu;
    private final IslandBankruptcyService bankruptcyService;
    private final IslandBankruptcyListener bankruptcyListener;
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
    private final OreGeneratorListener oreGeneratorListener;
    private final UpgradesModule upgradesModule;
    private final SafeTeleportEngine safeTeleportEngine;
    private final IslandWarpService warpService;
    private final IslandVaultService vaultService;
    private final IslandChatService chatService;
    private final IslandChatListener chatListener;
    private final IslandInactivityService inactivityService;

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
                new LocalIslandChatTransportAdapter());
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
        this.dynamicPricingEngine = new DynamicPricingEngine(config.shopConfig().dampingFactor());

        this.safeTeleportEngine = new SafeTeleportEngine(config.warpConfig().searchRadius());
        this.warpService = new IslandWarpService(
                persistence.islandWarpStoragePort(),
                safeTeleportEngine,
                null,
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
                null,
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
        this.chatListener = new IslandChatListener(chatService, authority.activeProfileProvider());

        BukkitPlayerActivityProvider activityProvider = new BukkitPlayerActivityProvider();
        this.inactivityService = new IslandInactivityService(
                persistence.islandStoragePort(),
                activityProvider,
                config.inactivityConfig().toPolicy(),
                null,
                null,
                persistence.outboxPort());

        this.biomeAdapter = new BukkitBiomeAdapter(persistence.islandStoragePort(), scheduler, worldName);

        this.missionService = new IslandMissionService(persistence.islandMissionStoragePort());
        this.missionService.registerMissions(config.missionConfig().missions());
        this.missionsMenu = new IslandMissionsMenu(
                missionService, persistence.islandStoragePort(), authority.sessionCoordinator(), scheduler);
        this.missionListener = new IslandMissionListener(
                missionService, persistence.islandStoragePort(), authority.sessionCoordinator(), scheduler);

        this.worldBorderAdapter = new WorldBorderPacketAdapter(scheduler);
        this.boundaryService = new IslandBoundaryService(worldBorderAdapter);
        this.boundaryListener = new IslandBoundaryListener(boundaryService, protectionListener, scheduler);
        this.boundaryListener.setStopBorderCrossing(config.settingsConfig().stopBorderCrossing());

        this.voidingAdapter = new FoliaIslandVoidingAdapter(scheduler, this.backpressureController);
        com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter dimensionSnapshotAdapter =
                new com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter(
                        plugin, persistence.islandStoragePort());
        this.islandBackupAdapter = new NbtIslandBackupAdapter(plugin.getDataFolder(), dimensionSnapshotAdapter);
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

        this.chunkScanner = new FoliaIslandChunkScanner(
                this.scheduler,
                config.levelConfig().blockWeights().keySet(),
                config.levelConfig().spawnerWeights().keySet());
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
        this.worthListener = new IslandWorthListener(this.worthService, this.protectionListener);

        this.dimensionService = new IslandDimensionService(
                persistence.islandUpgradeStoragePort(),
                config.dimensionConfig().mappings(),
                persistence.islandDimensionStoragePort());
        this.dimensionListener = new IslandDimensionListener(
                this.dimensionService,
                locationService,
                schematicEngine,
                this.scheduler,
                p -> authority.sessionCoordinator().activeProfile(p.getUniqueId()),
                worldName);

        this.limitService = new IslandLimitService(
                persistence.islandUpgradeStoragePort(), config.limitConfig().quotas());
        this.limitListener = new IslandLimitListener(
                this.limitService, this.protectionListener, config.limitConfig().bypassPermission());
        this.limitReconciler = new IslandLimitReconciler(this.scheduler, this.limitService);

        this.antiAbuseService = new IslandAntiAbuseService(
                persistence.antiAbuseStoragePort(),
                config.antiAbuseConfig().purgeInventoryOnReset(),
                config.antiAbuseConfig().quarantineDuration(),
                config.antiAbuseConfig().resetCooldown(),
                config.antiAbuseConfig().maxResetsPerDay(),
                config.antiAbuseConfig().resetWindowDuration(),
                config.antiAbuseConfig().coopJoinCooldown(),
                java.time.Clock.systemUTC());
        this.antiAbuseListener = new IslandAntiAbuseListener(
                persistence.islandStoragePort(), this.antiAbuseService, config.antiAbuseConfig());

        this.boosterService = new IslandBoosterService(
                persistence.islandBoosterStoragePort(),
                config.boosterConfig()::policy,
                config.boosterConfig().pauseWhenEmpty());
        this.boosterListener = new IslandBoosterListener(
                persistence.islandStoragePort(),
                this.boosterService,
                config.boosterConfig(),
                authority.sessionCoordinator(),
                this.scheduler);
        this.boosterMenu = new IslandBoosterMenu(
                persistence.islandStoragePort(),
                this.boosterService,
                config.boosterConfig(),
                authority.sessionCoordinator(),
                this.scheduler);

        this.bankruptcyService = new IslandBankruptcyService(
                persistence.islandBankruptcyStoragePort(),
                persistence.islandBankPort(),
                persistence.islandAuthorityPort(),
                config.bankConfig()::upkeepPolicy);
        this.bankruptcyListener = new IslandBankruptcyListener(
                this.bankruptcyService,
                this.protectionListener,
                persistence.islandStoragePort(),
                authority.sessionCoordinator(),
                this.scheduler);

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
            if (session == null) {
                return Optional.empty();
            }
            return Optional.of(new PlayerSessionRecord(
                    uuid,
                    session.activeProfileId(),
                    config.nodeConfig().nodeId(),
                    session.sessionEpoch(),
                    session.state(),
                    Instant.now().plusSeconds(60),
                    session.lastDurableVersion(),
                    null,
                    null,
                    null));
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

        this.upgradeService = new IslandUpgradeService(
                persistence.islandUpgradeStoragePort(), config.upgradesConfig().definitions());
        this.oreGeneratorListener = new OreGeneratorListener(
                this.upgradeService, config.generatorsConfig(), this.protectionListener.spatialIndex());
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

    public IslandMissionsMenu missionsMenu() {
        return missionsMenu;
    }

    public IslandMissionListener missionListener() {
        return missionListener;
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return worldBorderAdapter;
    }

    public IslandBoundaryService boundaryService() {
        return boundaryService;
    }

    public IslandBoundaryListener boundaryListener() {
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

    public FoliaIslandChunkScanner chunkScanner() {
        return chunkScanner;
    }

    public IslandWorthService worthService() {
        return worthService;
    }

    public IslandWorthListener worthListener() {
        return worthListener;
    }

    public IslandDimensionService dimensionService() {
        return dimensionService;
    }

    public IslandDimensionListener dimensionListener() {
        return dimensionListener;
    }

    public IslandLimitService limitService() {
        return limitService;
    }

    public IslandLimitListener limitListener() {
        return limitListener;
    }

    public IslandLimitReconciler limitReconciler() {
        return limitReconciler;
    }

    public IslandAntiAbuseService antiAbuseService() {
        return antiAbuseService;
    }

    public IslandAntiAbuseListener antiAbuseListener() {
        return antiAbuseListener;
    }

    public IslandBoosterService boosterService() {
        return boosterService;
    }

    public IslandBoosterListener boosterListener() {
        return boosterListener;
    }

    public IslandBoosterMenu boosterMenu() {
        return boosterMenu;
    }

    public IslandBankruptcyService bankruptcyService() {
        return bankruptcyService;
    }

    public IslandBankruptcyListener bankruptcyListener() {
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

    public OreGeneratorListener oreGeneratorListener() {
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

    public IslandChatListener chatListener() {
        return chatListener;
    }

    public IslandInactivityService inactivityService() {
        return inactivityService;
    }
}
