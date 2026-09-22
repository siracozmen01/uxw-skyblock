package com.uxplima.uxmskyblock.persistence.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The inbox against the databases a customer runs.
 *
 * <p>Every statement here compared a boolean column against the number 0 or set it to the number 1.
 * MariaDB and SQLite let that pass. PostgreSQL does not: it declares the column BOOLEAN and refuses
 * {@code boolean = integer}, so reading a player's notices threw on PostgreSQL. Nothing had ever
 * written a notification, so nothing had ever read one back, and the failure had nowhere to show.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class NotificationIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static SqlNotificationAdapter mariaAdapter;
    private static SqlNotificationAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new SqlNotificationAdapter(mariaDatabase.dataSource());
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new SqlNotificationAdapter(postgresDatabase.dataSource());
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @Order(1)
    @EnabledIfMariaDb
    @DisplayName("MariaDB: a notice is written, read, marked read and eventually swept")
    void mariaDbInbox() throws Exception {
        assertTheInboxWorks(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: a notice is written, read, marked read and eventually swept")
    void postgresInbox() throws Exception {
        assertTheInboxWorks(postgresDatabase, postgresAdapter);
    }

    private void assertTheInboxWorks(Database db, SqlNotificationAdapter adapter) throws Exception {
        ProfileId recipient = newProfile(db);
        Notification notice = new Notification(
                UUID.randomUUID(),
                recipient,
                NotificationCategory.KICK,
                "notification.kicked",
                1,
                "player\u001fOwner",
                false,
                null,
                null,
                Instant.now());

        adapter.saveNotification(notice);

        assertThat(adapter.countUnread(recipient)).describedAs("one unread").isEqualTo(1);
        List<Notification> pending = adapter.findPendingNotifications(recipient);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).payloadTypeId()).isEqualTo("notification.kicked");
        assertThat(pending.get(0).payloadData()).isEqualTo("player\u001fOwner");

        Instant readAt = Instant.now().minusSeconds(60);
        adapter.markAsRead(notice.notificationId(), readAt);

        assertThat(adapter.countUnread(recipient))
                .describedAs("nothing unread now")
                .isZero();
        assertThat(adapter.findPendingNotifications(recipient)).isEmpty();

        assertThat(adapter.purgeReadBefore(Instant.now().minusSeconds(3600)))
                .describedAs("read, but not old enough")
                .isZero();
        assertThat(adapter.purgeReadBefore(Instant.now()))
                .describedAs("read, and old enough")
                .isEqualTo(1);
        assertThat(adapter.countUnread(recipient)).isZero();
    }

    @Test
    @Order(3)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: marking everything read leaves nothing unread")
    void postgresMarkAll() throws Exception {
        ProfileId recipient = newProfile(postgresDatabase);
        for (int i = 0; i < 3; i++) {
            postgresAdapter.saveNotification(new Notification(
                    UUID.randomUUID(),
                    recipient,
                    NotificationCategory.SYSTEM,
                    "notification.entry",
                    1,
                    "body\u001fsomething " + i,
                    false,
                    null,
                    null,
                    Instant.now()));
        }
        assertThat(postgresAdapter.countUnread(recipient)).isEqualTo(3);

        postgresAdapter.markAllAsRead(recipient, Instant.now());

        assertThat(postgresAdapter.countUnread(recipient)).isZero();
    }

    private ProfileId newProfile(Database db) throws Exception {
        UUID player = UUID.randomUUID();
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        try (Connection conn = db.connection()) {
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                stmt.setString(1, player.toString());
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, player.toString());
                stmt.executeUpdate();
            }
        }
        return profile;
    }
}
