package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.NotificationConfiguration;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/**
 * What keeps the tables from growing for as long as the server runs, and what finishes the work a
 * crash left half done once the server is up again.
 */
final class Housekeeping implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Housekeeping.class.getName());

    private final SchedulerPort scheduler;
    private final NotificationConfiguration notificationConfig;
    private final @Nullable IslandRecycleService recycleService;
    private final com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService restoreService;
    private final com.uxplima.uxmskyblock.core.application.backup.BackupService backupService;
    private final com.uxplima.uxmskyblock.core.domain.storage.StorageBucket backupBucket;
    private final NotificationService notificationService;
    private final com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService activityFeedService;
    private final com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort economySagaPort;
    private final com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort journalPort;
    private @Nullable AutoCloseable sweep;

    Housekeeping(
            GameplayWiring gameplay, PersistenceBootstrap persistence, NotificationConfiguration notificationConfig) {
        this.scheduler = gameplay.scheduler();
        this.notificationConfig = Objects.requireNonNull(notificationConfig, "notificationConfig must not be null");
        this.recycleService = gameplay.recycleService();
        this.restoreService = gameplay.islandRestoreService();
        this.backupService = gameplay.backupService();
        this.backupBucket = gameplay.backupBucket();
        this.notificationService = gameplay.notificationService();
        this.activityFeedService = gameplay.activityFeedService();
        this.economySagaPort = persistence.economySagaPort();
        this.journalPort = persistence.mutationJournalPort();
    }

    /** Starts the sweeps, on the operator's interval. */
    void start() {
        this.sweep = scheduler.repeatAsync(
                () -> {
                    sweepReadNotifications();
                    sweepOldActivity();
                    sweepSettledRecoveryRecords();
                },
                notificationConfig.sweepInterval(),
                notificationConfig.sweepInterval());
    }

    /** Finishes what a crash left half done, once the worlds it writes into are loaded. */
    void recoverAfterStart() {
        recoverIncompleteRecycles();
        resumeInterruptedRestores();
    }

    /**
     * Drops the activity lines an island's feed has outgrown.
     *
     * <p>A feed is a digest of what happened lately, not a ledger. Nothing wrote a line until now
     * and nothing ever deleted one.
     */
    private void sweepOldActivity() {
        try {
            int swept = activityFeedService.purgeOlderThan(
                    java.time.Instant.now().minus(notificationConfig.activityRetention()));
            if (swept > 0) {
                LOGGER.fine(() -> "Swept " + swept + " activity events an island's feed had outgrown.");
            }
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING, "Sweeping the activity feed failed. The next sweep retries.", e);
        }
    }
    /**
     * Deletes the recovery records that have nothing left to recover.
     *
     * <p>A write-ahead inventory journal and an economy saga exist so a crash in the middle of
     * something can be finished or undone. Once one has committed or been undone it has done its
     * job, and nothing ever deleted one: the tables held every economic item movement and every
     * external money movement a server had ever made. A journal a crash left unreconciled stays,
     * and so does a saga that failed.
     */
    private void sweepSettledRecoveryRecords() {
        java.time.Instant before = java.time.Instant.now().minus(notificationConfig.recoveryRetention());
        try {
            int journals = journalPort.purgeSettledBefore(before);
            int sagas = economySagaPort.purgeSettledBefore(before);
            if (journals + sagas > 0) {
                LOGGER.fine(() ->
                        "Swept " + journals + " settled inventory journals and " + sagas + " settled economy sagas.");
            }
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    "Sweeping the settled recovery records failed. The next sweep retries.",
                    e);
        }
    }
    /**
     * Deletes the notices a player has already read and long since acted on.
     *
     * <p>Nothing wrote a notification until now and nothing ever deleted one, so the table would
     * have grown for as long as the server ran the moment anything started writing to it.
     */
    private void sweepReadNotifications() {
        try {
            int swept =
                    notificationService.purgeRead(java.time.Instant.now().minus(notificationConfig.readRetention()));
            if (swept > 0) {
                LOGGER.fine(() -> "Swept " + swept + " notifications that had been read.");
            }
        } catch (RuntimeException e) {
            LOGGER.log(
                    java.util.logging.Level.WARNING,
                    "Sweeping the notifications already read failed. The next sweep retries.",
                    e);
        }
    }
    /**
     * Writes a finished mission into the island's feed.
     *
     * <p>Only the mission service knows the moment a mission crosses its target: it happens inside
     * an advance, and the callers that trigger it are block breaks and hand-ins that know nothing
     * about it. The write is a row, so it hops off whichever thread the last block break arrived on.
     */
    void tellTheFeedWhenAMissionFinishes(GameplayWiring gameplay) {
        com.uxplima.uxmskyblock.core.application.mission.IslandMissionService missions = gameplay.missionService();
        if (missions == null) {
            return;
        }
        missions.setFinishedListener(finished -> scheduler.async(() -> {
            try {
                activityFeedService.record(
                        finished.islandId().value().toString(),
                        finished.profileId(),
                        com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType.MISSION_COMPLETED,
                        com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility.MEMBERS_ONLY,
                        "activity.mission_completed",
                        java.util.Map.of("mission", finished.definition().displayName()));
            } catch (RuntimeException e) {
                LOGGER.log(java.util.logging.Level.WARNING, "Writing a finished mission into the feed failed.", e);
            }
        }));
    }
    /**
     * Finishes the restores a stop left half done, once the worlds they write into are loaded.
     *
     * <p>A restore writes each unit down before it puts it back and again after. A node that stopped
     * between the two left an island half put back and frozen; this puts the rest back and opens it.
     */
    private void resumeInterruptedRestores() {
        scheduler.async(() -> {
            try {
                restoreService.resumeUnfinished(backupBucket, backupService::loadManifest);
            } catch (RuntimeException e) {
                LOGGER.log(
                        java.util.logging.Level.WARNING,
                        "Finishing the restores a stop left half done failed. The islands stay frozen.",
                        e);
            }
        });
    }
    /**
     * Finishes the island resets a crash left half done.
     *
     * <p>A reset deletes the island and then hands its grid slot back. A crash between the two
     * leaves the operation sitting in CANONICAL_DELETE and the slot marked allocated for an island
     * that no longer exists, so the grid never reuses it and the world grows a hole per crash. The
     * recovery for exactly this was written, said "startup recovery" in its own documentation, and
     * had no caller anywhere.
     *
     * <p>It runs off the thread that is starting the server, because it reads and writes rows.
     */
    private void recoverIncompleteRecycles() {
        IslandRecycleService recycleService = this.recycleService;
        if (recycleService == null) {
            return;
        }
        scheduler.async(() -> {
            try {
                recycleService.recoverIncompleteOperations();
            } catch (RuntimeException e) {
                LOGGER.log(
                        java.util.logging.Level.WARNING,
                        "Finishing the island resets a crash left half done failed.",
                        e);
            }
        });
    }

    @Override
    public void close() {
        AutoCloseable running = this.sweep;
        this.sweep = null;
        if (running == null) {
            return;
        }
        try {
            running.close();
        } catch (Exception e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Stopping the notification sweep failed.", e);
        }
    }
}
