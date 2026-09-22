package com.uxplima.uxmskyblock.persistence.enterprise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.backup.DatabaseBackupDialect;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.home.HomeId;
import com.uxplima.uxmskyblock.core.domain.home.HomeScope;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.persistence.activity.SqlActivityFeedAdapter;
import com.uxplima.uxmskyblock.persistence.backup.SqlDatabaseBackupAdapter;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import com.uxplima.uxmskyblock.persistence.home.SqlHomeStorageAdapter;
import com.uxplima.uxmskyblock.persistence.notification.SqlNotificationAdapter;
import com.uxplima.uxmskyblock.persistence.snapshot.SqlRootRelationalSnapshotAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnterprisePersistenceAdaptersTest {

    @TempDir
    Path tempDir;

    private PersistenceBootstrap bootstrap;
    private Database database;
    private SqlHomeStorageAdapter homeAdapter;
    private SqlActivityFeedAdapter activityAdapter;
    private SqlNotificationAdapter notificationAdapter;
    private SqlDatabaseBackupAdapter backupAdapter;
    private SqlRootRelationalSnapshotAdapter snapshotAdapter;

    private PlayerUuid playerUuid;
    private ProfileId profileId;
    private IslandId islandId;

    @BeforeEach
    void setUp() throws Exception {
        Path dbPath = tempDir.resolve("enterprise_test.db");
        database = Database.builder().sqlite(dbPath).build();
        bootstrap = new PersistenceBootstrap(database);

        homeAdapter = (SqlHomeStorageAdapter) bootstrap.homeStoragePort();
        activityAdapter = (SqlActivityFeedAdapter) bootstrap.activityFeedStoragePort();
        notificationAdapter = (SqlNotificationAdapter) bootstrap.notificationStoragePort();
        backupAdapter = (SqlDatabaseBackupAdapter) bootstrap.databaseBackupPort();
        snapshotAdapter = (SqlRootRelationalSnapshotAdapter) bootstrap.rootRelationalSnapshotPort();

        playerUuid = PlayerUuid.of(UUID.randomUUID());
        profileId = new ProfileId(UUID.randomUUID());
        islandId = IslandId.of(UUID.randomUUID());

        try (java.sql.Connection conn = database.connection()) {
            try (java.sql.PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, playerUuid.value().toString());
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, profileId.value().toString());
                ps.setString(2, playerUuid.value().toString());
                ps.executeUpdate();
            }
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES (?, ?, ?)")) {
                ps.setString(1, islandId.value().toString());
                ps.setString(2, profileId.value().toString());
                ps.setString(3, playerUuid.value().toString());
                ps.executeUpdate();
            }
        }
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.close();
        }
    }

    @Test
    @DisplayName("HomeStorageAdapter: saves, queries, and deletes homes")
    void testHomeStorageOperations() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Home home1 = new Home(
                HomeId.random(),
                profileId,
                islandId,
                "main",
                HomeScope.PERSONAL,
                "world",
                10.0,
                64.0,
                10.0,
                0.0f,
                0.0f,
                now,
                now);
        Home home2 = new Home(
                HomeId.random(),
                profileId,
                islandId,
                "farm",
                HomeScope.CO_OP,
                "world",
                20.0,
                65.0,
                20.0,
                90.0f,
                0.0f,
                now,
                now);

        homeAdapter.saveHome(home1);
        homeAdapter.saveHome(home2);

        assertThat(homeAdapter.countHomes(profileId)).isEqualTo(2);

        Optional<Home> found = homeAdapter.findHome(profileId, "main");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("main");
        assertThat(found.get().x()).isEqualTo(10.0);

        List<Home> profileHomes = homeAdapter.findHomesByProfileId(profileId);
        assertThat(profileHomes).hasSize(2);

        List<Home> islandHomes = homeAdapter.findHomesByIslandId(islandId);
        assertThat(islandHomes).hasSize(2);

        // Update home1 coordinates
        Home updatedHome1 = new Home(
                home1.id(),
                profileId,
                islandId,
                "main",
                HomeScope.PERSONAL,
                "world_nether",
                100.0,
                70.0,
                100.0,
                180.0f,
                0.0f,
                now,
                now.plusSeconds(60));
        homeAdapter.saveHome(updatedHome1);

        Optional<Home> updatedFound = homeAdapter.findHome(profileId, "main");
        assertThat(updatedFound).isPresent();
        assertThat(updatedFound.get().worldName()).isEqualTo("world_nether");
        assertThat(updatedFound.get().x()).isEqualTo(100.0);

        // Delete home
        boolean deleted = homeAdapter.deleteHome(profileId, "farm");
        assertThat(deleted).isTrue();
        assertThat(homeAdapter.countHomes(profileId)).isEqualTo(1);
        assertThat(homeAdapter.findHome(profileId, "farm")).isEmpty();
    }

    @Test
    @DisplayName("ActivityFeedAdapter: appends and retrieves activity events")
    void testActivityFeedOperations() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ActivityEvent event1 = new ActivityEvent(
                UUID.randomUUID(),
                islandId.value().toString(),
                profileId,
                ActivityEventType.MEMBER_JOINED,
                ActivityVisibility.PUBLIC,
                "MEMBER_JOINED",
                1,
                "{\"player\": \"Alice\"}",
                now);
        ActivityEvent event2 = new ActivityEvent(
                UUID.randomUUID(),
                islandId.value().toString(),
                profileId,
                ActivityEventType.BANK_DEPOSIT,
                ActivityVisibility.MEMBERS_ONLY,
                "DEPOSIT",
                1,
                "{\"amount\": 5000}",
                now.plusSeconds(10));

        activityAdapter.appendEvent(event1);
        activityAdapter.appendEvent(event2);

        List<ActivityEvent> events =
                activityAdapter.findEventsByInstanceId(islandId.value().toString(), 10);
        assertThat(events).hasSize(2);
        // Latest first
        assertThat(events.get(0).eventType()).isEqualTo(ActivityEventType.BANK_DEPOSIT);
        assertThat(events.get(1).eventType()).isEqualTo(ActivityEventType.MEMBER_JOINED);

        // A feed is a digest of what happened lately, not a ledger, and nothing ever deleted a line.
        assertThat(activityAdapter.purgeEventsBefore(now.minusSeconds(60)))
                .describedAs("nothing old enough yet")
                .isZero();
        assertThat(activityAdapter.purgeEventsBefore(now.plusSeconds(5)))
                .describedAs("the older of the two")
                .isEqualTo(1);
        assertThat(activityAdapter.findEventsByInstanceId(islandId.value().toString(), 10))
                .hasSize(1);
    }

    @Test
    @DisplayName("NotificationAdapter: saves, queries pending, marks read, and counts unread")
    void testNotificationOperations() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Notification notif1 = new Notification(
                UUID.randomUUID(),
                profileId,
                NotificationCategory.SYSTEM,
                "ALERT",
                1,
                "{\"msg\": \"Welcome!\"}",
                false,
                null,
                now.plusSeconds(3600),
                now);
        Notification notif2 = new Notification(
                UUID.randomUUID(),
                profileId,
                NotificationCategory.BANK_ACTIVITY,
                "PAYOUT",
                1,
                "{\"coins\": 100}",
                false,
                null,
                now.plusSeconds(3600),
                now);

        notificationAdapter.saveNotification(notif1);
        notificationAdapter.saveNotification(notif2);

        assertThat(notificationAdapter.countUnread(profileId)).isEqualTo(2);

        List<Notification> pending = notificationAdapter.findPendingNotifications(profileId);
        assertThat(pending).hasSize(2);

        // Mark notif1 as read
        notificationAdapter.markAsRead(notif1.notificationId(), now.plusSeconds(5));
        assertThat(notificationAdapter.countUnread(profileId)).isEqualTo(1);

        // Mark all as read
        notificationAdapter.markAllAsRead(profileId, now.plusSeconds(10));
        assertThat(notificationAdapter.countUnread(profileId)).isEqualTo(0);
        assertThat(notificationAdapter.findPendingNotifications(profileId)).isEmpty();
    }

    @Test
    @DisplayName("DatabaseBackupAdapter: captures backup and requires confirmation for restore")
    void testDatabaseBackupOperations() {
        byte[] backup = backupAdapter.captureDatabaseBackup(DatabaseBackupDialect.SQLITE);
        assertThat(backup).isNotEmpty();

        // Fails without confirmation
        assertThatThrownBy(() -> backupAdapter.restoreDatabaseBackup(backup, DatabaseBackupDialect.SQLITE, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Disaster recovery requires explicit administrator confirmation");

        // Succeeds with confirmation
        backupAdapter.restoreDatabaseBackup(backup, DatabaseBackupDialect.SQLITE, true);
    }

    @Test
    @DisplayName("RootRelationalSnapshotAdapter: captures and faithfully restores island snapshot")
    void testRootRelationalSnapshotOperations() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Home home = new Home(
                HomeId.random(),
                profileId,
                islandId,
                "spawn",
                HomeScope.CO_OP,
                "world",
                5.0,
                64.0,
                5.0,
                0.0f,
                0.0f,
                now,
                now);
        homeAdapter.saveHome(home);

        PrimaryGameplayRootRef rootRef = new PrimaryGameplayRootRef(
                GameModeInstanceId.of(UUID.randomUUID()), islandId.value().toString(), "ISLAND", now);

        byte[] snapshot = snapshotAdapter.captureRelationalSnapshot(rootRef, 1);
        assertThat(snapshot).isNotEmpty();

        // Delete home
        homeAdapter.deleteHome(profileId, "spawn");
        assertThat(homeAdapter.findHome(profileId, "spawn")).isEmpty();

        // Restore snapshot
        snapshotAdapter.restoreRelationalSnapshot(
                rootRef, snapshot, com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode.FULL_ISLAND);

        // Verify home is restored
        Optional<Home> restoredHome = homeAdapter.findHome(profileId, "spawn");
        assertThat(restoredHome).isPresent();
        assertThat(restoredHome.get().name()).isEqualTo("spawn");
        assertThat(restoredHome.get().x()).isEqualTo(5.0);
    }
}
