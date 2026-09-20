package com.uxplima.uxmskyblock.persistence.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchState;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.jspecify.annotations.Nullable;
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

@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("NullAway")
class PlayerProfileSwitchIntegrationTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    private static PlayerProfileSwitchAdapter mariaAdapter;
    private static PlayerProfileSwitchAdapter postgresAdapter;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
            new MigrationRunner(mariaDatabase).apply(SkyblockMigrations.getMigrations(mariaDatabase.dialect()));
            mariaAdapter = new PlayerProfileSwitchAdapter(mariaDatabase);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            new MigrationRunner(postgresDatabase).apply(SkyblockMigrations.getMigrations(postgresDatabase.dialect()));
            postgresAdapter = new PlayerProfileSwitchAdapter(postgresDatabase);
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
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Full 2-phase profile switch lifecycle and atomic commit")
    void mariaDbFullSwitchLifecycle() {
        testFullLifecycle(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(2)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Full 2-phase profile switch lifecycle and atomic commit")
    void postgresFullSwitchLifecycle() {
        testFullLifecycle(postgresDatabase, postgresAdapter);
    }

    @Test
    @Order(3)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB: Concurrent switch reservation rejected via atomic CAS")
    void mariaDbConcurrentReservationRejected() {
        testConcurrentReservation(mariaDatabase, mariaAdapter);
    }

    @Test
    @Order(4)
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL: Concurrent switch reservation rejected via atomic CAS")
    void postgresConcurrentReservationRejected() {
        testConcurrentReservation(postgresDatabase, postgresAdapter);
    }

    private void testFullLifecycle(Database db, PlayerProfileSwitchAdapter adapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId p1 = ProfileId.of(UUID.randomUUID());
        ProfileId p2 = ProfileId.of(UUID.randomUUID());
        seedPlayer(db, player, p1, p2);

        UUID opId = UUID.randomUUID();
        byte[] p1Snapshot = new byte[] {10, 20, 30};
        byte[] p2Snapshot = new byte[] {40, 50, 60};

        // Reserve
        Result<ProfileSwitchOperation.Preparing, String> reserveRes =
                adapter.reserveSwitch(opId, player, p1, p2, NODE_A, 1);
        assertThat(reserveRes.isOk()).isTrue();

        // Source snapshot
        Result<ProfileSwitchOperation.SourceSnapshotted, String> snapRes =
                adapter.recordSourceSnapshot(opId, p1Snapshot);
        assertThat(snapRes.isOk()).isTrue();

        // Target loaded
        Result<ProfileSwitchOperation.TargetLoaded, String> loadRes = adapter.recordTargetLoaded(opId, p2Snapshot);
        assertThat(loadRes.isOk()).isTrue();

        // Target intent
        Result<ProfileSwitchOperation.TargetApplyIntent, String> intentRes = adapter.recordTargetApplyIntent(opId);
        assertThat(intentRes.isOk()).isTrue();

        // Player applied
        Result<ProfileSwitchOperation.PlayerApplied, String> appliedRes = adapter.recordPlayerApplied(opId);
        assertThat(appliedRes.isOk()).isTrue();

        // Commit
        Result<ProfileSwitchOperation.Committed, String> commitRes = adapter.commitSwitch(opId, player, p2, NODE_A, 1);
        assertThat(commitRes.isOk()).isTrue();
        assertThat(commitRes.orElseThrow().state()).isEqualTo(ProfileSwitchState.COMMITTED);

        // Verify in DB
        assertDbState(db, player, p2, null);
    }

    private void testConcurrentReservation(Database db, PlayerProfileSwitchAdapter adapter) {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId p1 = ProfileId.of(UUID.randomUUID());
        ProfileId p2 = ProfileId.of(UUID.randomUUID());
        seedPlayer(db, player, p1, p2);

        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();

        Result<ProfileSwitchOperation.Preparing, String> res1 = adapter.reserveSwitch(opId1, player, p1, p2, NODE_A, 1);
        assertThat(res1.isOk()).isTrue();

        Result<ProfileSwitchOperation.Preparing, String> res2 = adapter.reserveSwitch(opId2, player, p1, p2, NODE_A, 1);
        assertThat(res2.isErr()).isTrue();
        assertThat(res2.errorOrThrow()).isEqualTo("PROFILE_SWITCH_ALREADY_IN_PROGRESS");
    }

    private static String futureTimestampSql(Dialect dialect, int seconds) {
        return switch (dialect) {
            case MYSQL -> "CURRENT_TIMESTAMP + INTERVAL " + seconds + " SECOND";
            case POSTGRES -> "CURRENT_TIMESTAMP + INTERVAL '" + seconds + " seconds'";
            case SQLITE -> "DATETIME('now', '+" + seconds + " seconds')";
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect: " + dialect);
        };
    }

    private void seedPlayer(Database db, PlayerUuid player, ProfileId p1, ProfileId p2) {
        try (Connection conn = db.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id, active_switch_operation_id) VALUES (?, NULL, NULL)")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, p1.toString());
                ps.setString(2, player.toString());
                ps.executeUpdate();

                ps.setString(1, p2.toString());
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                ps.setString(1, p1.toString());
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_sessions (player_uuid, active_profile_id, authoritative_node, session_epoch, state, lease_expires_at) "
                            + "VALUES (?, ?, ?, 1, 'ACTIVE', " + futureTimestampSql(db.dialect(), 60) + ")")) {
                ps.setString(1, player.toString());
                ps.setString(2, p1.toString());
                ps.setString(3, NODE_A.value());
                ps.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertDbState(
            Database db, PlayerUuid player, ProfileId expectedProfile, @Nullable String expectedSwitchOp) {
        try (Connection conn = db.connection()) {
            try (PreparedStatement ps =
                    conn.prepareStatement("SELECT active_profile_id FROM player_sessions WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("active_profile_id")).isEqualTo(expectedProfile.toString());
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT active_profile_id, active_switch_operation_id FROM player_accounts WHERE player_uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("active_profile_id")).isEqualTo(expectedProfile.toString());
                    assertThat(rs.getString("active_switch_operation_id")).isEqualTo(expectedSwitchOp);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
