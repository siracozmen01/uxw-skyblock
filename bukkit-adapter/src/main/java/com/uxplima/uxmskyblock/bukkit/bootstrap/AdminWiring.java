package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;

import org.bukkit.plugin.java.JavaPlugin;

import com.uxplima.uxmskyblock.bukkit.antiabuse.IslandAntiAbuseListener;
import com.uxplima.uxmskyblock.bukkit.freeze.BukkitIslandVisitorEvictionAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitIslandArchivalAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitIslandRecycleAdapter;
import com.uxplima.uxmskyblock.bukkit.inactivity.BukkitPlayerActivityProvider;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.recycle.FoliaIslandVoidingAdapter;
import com.uxplima.uxmskyblock.bukkit.recycle.NbtIslandBackupAdapter;
import com.uxplima.uxmskyblock.bukkit.snapshot.WorldDimensionSnapshotAdapter;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandCacheEviction;
import com.uxplima.uxmskyblock.core.application.performance.AdaptiveBackpressureController;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.application.snapshot.WorldDimensionSnapshotPort;
import com.uxplima.uxmskyblock.core.application.storage.ObjectStoragePort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * Handles administrative domain services, backup/restore snapshots, inactivity scans,
 * island recycling/voiding, and anti-abuse subsystems.
 */
public final class AdminWiring {

    private final IslandAdminFreezeService freezeService;
    private final IslandInactivityService inactivityService;
    private final FoliaIslandVoidingAdapter voidingAdapter;
    private final WorldDimensionSnapshotPort worldDimensionSnapshotPort;
    private final NbtIslandBackupAdapter islandBackupAdapter;
    private final IslandRecycleService recycleService;
    private final @Nullable IslandResetConfirmationMenu resetConfirmationMenu;
    private final ObjectStoragePort objectStoragePort;
    private final BackupService backupService;
    private final IslandRestoreService islandRestoreService;
    private final IslandBackupService islandBackupService;
    private final IslandAntiAbuseService antiAbuseService;
    private final @Nullable IslandAntiAbuseListener antiAbuseListener;

    public AdminWiring(
            JavaPlugin plugin,
            ConfigurationWiring config,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            IslandProtectionListener protectionListener,
            BukkitIslandVisitorEvictionAdapter visitorEvictionAdapter,
            IslandAdminFreezeService freezeService,
            SchedulerPort scheduler,
            AdaptiveBackpressureController backpressureController,
            IslandCacheEviction cacheEviction,
            ObjectStoragePort objectStorage) {
        this.freezeService = Objects.requireNonNull(freezeService, "freezeService must not be null");
        this.voidingAdapter = new FoliaIslandVoidingAdapter(scheduler, backpressureController);

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
                persistence.outboxPort(),
                // The same lock the other island writers hold, or it locks nothing.
                persistence.islandMutationLock());

        this.worldDimensionSnapshotPort = new WorldDimensionSnapshotAdapter(
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
                persistence.islandRecycleOperationPort(),
                java.time.Clock.systemUTC(),
                config.antiAbuseConfig().resetChallengeTtl(),
                cacheEviction);
        this.resetConfirmationMenu = new IslandResetConfirmationMenu(
                recycleService,
                persistence.islandStoragePort(),
                authority.sessionCoordinator(),
                scheduler,
                config.messages());

        this.objectStoragePort = Objects.requireNonNull(objectStorage, "objectStorage must not be null");
        this.backupService = new BackupService(persistence.backupCatalogPort(), this.objectStoragePort);
        this.islandRestoreService = new IslandRestoreService(
                persistence.backupCatalogPort(),
                this.objectStoragePort,
                persistence.rootRelationalSnapshotPort(),
                this.worldDimensionSnapshotPort);
        this.islandBackupService = new IslandBackupService(
                this.backupService,
                persistence.rootRelationalSnapshotPort(),
                this.worldDimensionSnapshotPort,
                pluginVersionOf(plugin));

        this.antiAbuseService = new IslandAntiAbuseService(
                persistence.antiAbuseStoragePort(),
                config.antiAbuseConfig().purgeInventoryOnReset(),
                config.antiAbuseConfig().quarantineDuration(),
                config.antiAbuseConfig().resetCooldown(),
                config.antiAbuseConfig().maxResetsPerDay(),
                config.antiAbuseConfig().resetWindowDuration(),
                config.antiAbuseConfig().coopJoinCooldown(),
                config.antiAbuseConfig().quarantineLookupTtl(),
                java.time.Clock.systemUTC());
        this.antiAbuseListener = config.moduleSettings().isModuleEnabled("anti-abuse")
                // The same index the protection listener uses, so an island one of them has seen is
                // an island the other has too, and so a miss is filled off the thread rather than
                // where the touch arrived.
                ? new IslandAntiAbuseListener(
                        persistence.islandStoragePort(),
                        this.antiAbuseService,
                        config.antiAbuseConfig(),
                        protectionListener.spatialIndex(),
                        config.messages())
                : null;
    }

    public IslandAdminFreezeService freezeService() {
        return freezeService;
    }

    public IslandInactivityService inactivityService() {
        return inactivityService;
    }

    public FoliaIslandVoidingAdapter voidingAdapter() {
        return voidingAdapter;
    }

    public WorldDimensionSnapshotPort worldDimensionSnapshotPort() {
        return worldDimensionSnapshotPort;
    }

    public NbtIslandBackupAdapter islandBackupAdapter() {
        return islandBackupAdapter;
    }

    public IslandRecycleService recycleService() {
        return recycleService;
    }

    public @Nullable IslandResetConfirmationMenu resetConfirmationMenu() {
        return resetConfirmationMenu;
    }

    public ObjectStoragePort objectStoragePort() {
        return objectStoragePort;
    }

    /**
     * The plugin's own version, for the provenance field of a backup manifest.
     *
     * <p>A server that does not publish its plugin metadata still takes backups. The version is
     * written down so a reader knows what made the file, and a file that cannot say is better than
     * no file at all.
     */
    private static String pluginVersionOf(org.bukkit.plugin.Plugin plugin) {
        try {
            io.papermc.paper.plugin.configuration.PluginMeta meta = plugin.getPluginMeta();
            String version = meta.getVersion();
            return version.isBlank() ? "unknown" : version;
        } catch (RuntimeException | LinkageError unavailable) {
            return "unknown";
        }
    }

    /** The thing that makes the backups the restore command puts back. */
    public IslandBackupService islandBackupService() {
        return islandBackupService;
    }

    public BackupService backupService() {
        return backupService;
    }

    public IslandRestoreService islandRestoreService() {
        return islandRestoreService;
    }

    public IslandAntiAbuseService antiAbuseService() {
        return antiAbuseService;
    }

    public @Nullable IslandAntiAbuseListener antiAbuseListener() {
        return antiAbuseListener;
    }
}
