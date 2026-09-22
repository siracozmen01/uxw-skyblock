package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
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
import com.uxplima.uxmskyblock.bukkit.notification.IslandNotificationListener;
import com.uxplima.uxmskyblock.bukkit.performance.IslandRedstoneOptimizationListener;
import com.uxplima.uxmskyblock.bukkit.protection.CategoricalInteractablesListener;
import com.uxplima.uxmskyblock.bukkit.protection.ObsidianRecoveryListener;
import com.uxplima.uxmskyblock.bukkit.protection.VoidProtectionListener;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.upgrade.OreGeneratorListener;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultListener;
import com.uxplima.uxmskyblock.bukkit.vault.IslandVaultWindow;
import com.uxplima.uxmskyblock.bukkit.ward.KineticWardListener;
import com.uxplima.uxmskyblock.bukkit.world.AsyncStructureSuppressionListener;
import com.uxplima.uxmskyblock.bukkit.worth.FoliaIslandChunkScanner;
import com.uxplima.uxmskyblock.bukkit.worth.IslandWorthListener;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.border.IslandBorderService;
import com.uxplima.uxmskyblock.core.application.border.IslandSizeAllowance;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatTransportPort;
import com.uxplima.uxmskyblock.core.application.dimension.IslandDimensionService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.home.HomeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.membership.IslandMembershipService;
import com.uxplima.uxmskyblock.core.application.membership.MemberAllowance;
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
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.storage.LocalFilesystemStorageAdapter;
import org.jspecify.annotations.Nullable;

/**
 * Composition root for gameplay subsystems: island creation, economy, social, admin, protection, and environment.
 */
public final class GameplayWiring {

    private final SchedulerPort scheduler;
    private final AdaptiveBackpressureController backpressureController;
    private final IslandAccessService accessService;
    private final BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter;
    private final IslandProtectionListener protectionListener;

    private final GameplayCreationWiring creationWiring;
    private final AdminWiring adminWiring;
    private final StorageBucket backupBucket;
    private final IslandCacheEviction cacheEviction;
    private final IslandBorderService borderService;
    private final IslandMembershipService membershipService;
    private final EconomicWiring economicWiring;
    private final SocialWiring socialWiring;
    private final GameplayProtectionWiring protectionWiring;
    private final GameplayEnvironmentWiring environmentWiring;

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
                        plugin.getDataFolder().toPath().resolve("backups")),
                new StorageBucket(PersistenceWiring.DEFAULT_BACKUP_BUCKET));
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
            ObjectStoragePort objectStorage,
            StorageBucket backupBucket) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.backpressureController =
                Objects.requireNonNull(backpressureController, "backpressureController must not be null");
        this.accessService = Objects.requireNonNull(accessService, "accessService must not be null");
        this.visitorEvictionAdapter =
                Objects.requireNonNull(visitorEvictionAdapter, "visitorEvictionAdapter must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");

        String worldName = config.nodeConfig().worldName();

        this.economicWiring = new EconomicWiring(
                plugin, config, persistence, authority, protectionListener, scheduler, economyBridgeSupplier);

        this.creationWiring = new GameplayCreationWiring(
                config,
                persistence,
                authority,
                scheduler,
                backpressureController,
                accessService,
                this.economicWiring.rewardInboxService(),
                this.economicWiring.upgradeService());

        this.socialWiring = new SocialWiring(
                config,
                persistence,
                authority,
                allianceService,
                temporaryAccessService,
                this.economicWiring.upgradeService(),
                chatTransport,
                scheduler);

        this.backupBucket = java.util.Objects.requireNonNull(backupBucket, "backupBucket must not be null");
        // One place that forgets an island. Each service says once how to forget one, and erasing
        // an island says it happened.
        this.cacheEviction = new IslandCacheEviction();
        this.cacheEviction.whenForgotten(this.economicWiring.upgradeService()::invalidateCache);

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
                this.cacheEviction,
                objectStorage);

        // A skyblock with no way to make a team. The cap is the member limit upgrade's own tier
        // property, so an operator raises it by editing upgrades.conf rather than by asking for a
        // release.
        this.membershipService = new IslandMembershipService(
                persistence.islandStoragePort(),
                persistence.islandMutationLock(),
                new MemberAllowance(this.economicWiring.upgradeService()));

        this.environmentWiring = new GameplayEnvironmentWiring(
                config,
                persistence,
                authority,
                protectionListener,
                creationWiring.locationService(),
                creationWiring.schematicEngine(),
                scheduler,
                worldName,
                this.economicWiring.upgradeService());

        // The island size upgrade had five tiers, a cost for each and a radius on each, and nothing
        // read the radius: an island that paid for the top tier reached exactly as far as one that
        // had paid nothing. The edge follows the tier now, and the protection index is told, because
        // it is what answers for every block a player touches.
        this.borderService = new IslandBorderService(
                persistence.islandStoragePort(),
                new IslandSizeAllowance(this.economicWiring.upgradeService()),
                persistence.islandMutationLock());
        this.economicWiring.upgradeService().whenUpgraded((islandId, upgradeId, newTier) -> {
            if (!UpgradeId.SIZE.equals(upgradeId)) {
                return;
            }
            borderService
                    .applyAllowance(islandId)
                    .ifPresent(moved -> protectionListener
                            .spatialIndex()
                            .indexIsland(moved.island(), moved.location().worldName()));
        });

        // The rest of the caches register as they are built, after the wiring that owns them.
        this.cacheEviction.whenForgotten(this.environmentWiring.limitService()::clearIsland);
        this.cacheEviction.whenForgotten(this.environmentWiring.dimensionService()::resetIslandDimensions);
        this.cacheEviction.whenForgotten(this.economicWiring.worthService()::forgetIsland);
        // Three more that hold something for every island a player has merely walked on. Each of
        // them answers a question on the movement or interaction path, and each of them remembers
        // the answer so the path is not a query. An island id is a fresh uuid every time, so an
        // erased island's entry is never read again and nothing was letting go of it.
        this.cacheEviction.whenForgotten(temporaryAccessService::forgetIsland);
        this.cacheEviction.whenForgotten(this.adminWiring.antiAbuseService()::forgetIsland);
        this.cacheEviction.whenForgotten(this.economicWiring.bankruptcyService()::forgetIsland);
        this.cacheEviction.whenForgotten(freezeService::forgetIsland);

        this.protectionWiring = new GameplayProtectionWiring(
                config, persistence, authority, protectionListener, temporaryAccessService);
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
        return creationWiring.presetCatalog();
    }

    public StarterSchematicEngine schematicEngine() {
        return creationWiring.schematicEngine();
    }

    public SpiralGridCoordinateAllocator coordinateAllocator() {
        return creationWiring.coordinateAllocator();
    }

    public SpiralWorldGridService gridService() {
        return creationWiring.gridService();
    }

    public CreateIslandUseCase createIslandUseCase() {
        return creationWiring.createIslandUseCase();
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
        return creationWiring.locationService();
    }

    public IslandBankService bankService() {
        return economicWiring.bankService();
    }

    public IslandLeaderboardService leaderboardService() {
        return creationWiring.leaderboardService();
    }

    public IslandSeasonService seasonService() {
        return creationWiring.seasonService();
    }

    public ActivityFeedService activityFeedService() {
        return socialWiring.activityFeedService();
    }

    public IslandNotificationListener notificationListener() {
        return socialWiring.notificationListener();
    }

    public @Nullable IslandVaultWindow vaultWindow() {
        return socialWiring.vaultWindow();
    }

    public @Nullable IslandVaultListener vaultListener() {
        return socialWiring.vaultListener();
    }

    public HomeService homeService() {
        return socialWiring.homeService();
    }

    public IslandSocialService socialService() {
        return socialWiring.socialService();
    }

    public IslandAllianceService allianceService() {
        return socialWiring.allianceService();
    }

    public com.uxplima.uxmskyblock.core.application.shop.IslandShopService shopService() {
        return economicWiring.shopService();
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
        return environmentWiring.biomeAdapter();
    }

    public IslandMissionService missionService() {
        return creationWiring.missionService();
    }

    public @Nullable IslandMissionsMenu missionsMenu() {
        return creationWiring.missionsMenu();
    }

    public @Nullable IslandMissionListener missionListener() {
        return creationWiring.missionListener();
    }

    public WorldBorderPacketAdapter worldBorderAdapter() {
        return environmentWiring.worldBorderAdapter();
    }

    public IslandBoundaryService boundaryService() {
        return environmentWiring.boundaryService();
    }

    public @Nullable IslandBoundaryListener boundaryListener() {
        return environmentWiring.boundaryListener();
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
        return environmentWiring.dimensionService();
    }

    public @Nullable IslandDimensionListener dimensionListener() {
        return environmentWiring.dimensionListener();
    }

    public IslandLimitService limitService() {
        return environmentWiring.limitService();
    }

    public @Nullable IslandLimitListener limitListener() {
        return environmentWiring.limitListener();
    }

    public @Nullable IslandLimitReconciler limitReconciler() {
        return environmentWiring.limitReconciler();
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
        return creationWiring.islandNameService();
    }

    public KineticWardService kineticWardService() {
        return protectionWiring.kineticWardService();
    }

    public KineticWardListener kineticWardListener() {
        return protectionWiring.kineticWardListener();
    }

    public ObsidianRecoveryListener obsidianRecoveryListener() {
        return protectionWiring.obsidianRecoveryListener();
    }

    public VoidProtectionListener voidProtectionListener() {
        return protectionWiring.voidProtectionListener();
    }

    public CategoricalInteractablesListener categoricalInteractablesListener() {
        return protectionWiring.categoricalInteractablesListener();
    }

    public IslandRedstoneOptimizationListener redstoneOptimizationListener() {
        return protectionWiring.redstoneOptimizationListener();
    }

    public AsyncStructureSuppressionListener structureSuppressionListener() {
        return protectionWiring.structureSuppressionListener();
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

    /** The bucket the operator named for backups, so a restore reads back from where one was written. */
    public StorageBucket backupBucket() {
        return backupBucket;
    }

    public BackupService backupService() {
        return adminWiring.backupService();
    }

    public IslandRestoreService islandRestoreService() {
        return adminWiring.islandRestoreService();
    }

    public IslandBackupService islandBackupService() {
        return adminWiring.islandBackupService();
    }

    public IslandMembershipService membershipService() {
        return membershipService;
    }
}
