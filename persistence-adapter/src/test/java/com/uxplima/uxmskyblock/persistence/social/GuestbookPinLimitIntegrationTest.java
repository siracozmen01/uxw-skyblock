package com.uxplima.uxmskyblock.persistence.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
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
 * The guestbook pin limit is one statement, and both engines have to accept it.
 *
 * <p>The count lives inside the UPDATE, wrapped in a derived table, because MariaDB refuses a bare
 * subquery over the table the UPDATE is working on. A statement that only ever ran against SQLite
 * proves nothing about the database a customer runs.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class GuestbookPinLimitIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerIslandSocialAdapter mariaAdapter;
    private static PlayerIslandSocialAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerIslandSocialAdapter(mariaDatabase);
        }
        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerIslandSocialAdapter(postgresDatabase);
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
    @DisplayName("MariaDB: three pins go on, the fourth is refused, and another island is out of reach")
    void mariaDbPinLimit() throws Exception {
        assertThePinLimitHolds(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: three pins go on, the fourth is refused, and another island is out of reach")
    void postgresPinLimit() throws Exception {
        assertThePinLimitHolds(postgresDatabase, postgresAdapter);
    }

    private void assertThePinLimitHolds(Database db, PlayerIslandSocialAdapter adapter) throws Exception {
        ProfileId author = newProfile(db);
        SocialSubjectRef subject = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));
        SocialSubjectRef other = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));

        String[] ids = new String[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID().toString();
            adapter.saveGuestbookEntry(new GuestbookEntry(
                    ids[i],
                    subject,
                    author,
                    "Message " + i,
                    false,
                    false,
                    Instant.now().plusSeconds(i)));
        }

        for (int i = 0; i < 3; i++) {
            assertThat(adapter.pinGuestbookEntryWithin(subject, ids[i], 3))
                    .describedAs("pin number %d", i + 1)
                    .isTrue();
        }
        assertThat(adapter.pinGuestbookEntryWithin(subject, ids[3], 3))
                .describedAs("the fourth, on a page that keeps three")
                .isFalse();
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(3);

        assertThat(adapter.pinGuestbookEntryWithin(other, ids[4], 3))
                .describedAs("another island naming an entry that is not on its page")
                .isFalse();
        assertThat(adapter.setGuestbookHidden(other, ids[4], true)).isFalse();
        assertThat(adapter.deleteGuestbookEntry(other, ids[4])).isFalse();

        assertThat(adapter.unpinGuestbookEntry(subject, ids[0])).isTrue();
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(2);
        assertThat(adapter.pinGuestbookEntryWithin(subject, ids[3], 3))
                .describedAs("a slot came free, so the fourth goes on now")
                .isTrue();
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
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, created_at) VALUES (?, ?, ?)")) {
                stmt.setString(1, profile.value().toString());
                stmt.setString(2, player.toString());
                stmt.setTimestamp(3, Timestamp.from(Instant.now()));
                stmt.executeUpdate();
            }
        }
        return profile;
    }
}
