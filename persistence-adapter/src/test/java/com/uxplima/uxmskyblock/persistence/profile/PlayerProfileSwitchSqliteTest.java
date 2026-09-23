package com.uxplima.uxmskyblock.persistence.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchOperation;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileSwitchState;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerProfileSwitchSqliteTest {

    @TempDir
    Path tempDir;

    private Database database;
    private PlayerProfileSwitchAdapter adapter;

    private final PlayerUuid playerUuid = PlayerUuid.of(UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444"));
    private final ProfileId profileP1 = ProfileId.of(UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444"));
    private final ProfileId profileP2 = ProfileId.of(UUID.fromString("cccccccc-1111-2222-3333-444444444444"));
    private final ServerNodeId nodeA = ServerNodeId.of("node-alpha");

    @BeforeEach
    void setUp() throws IOException {
        Path dbFile = tempDir.resolve("switch_test.db");
        database = DatabaseTestFixture.createSqliteFile(dbFile);

        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(Dialect.SQLITE));

        adapter = new PlayerProfileSwitchAdapter(database);
        seedPlayerAccountAndProfiles(profileP1);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (database != null) {
            database.close();
        }
    }

    private void seedPlayerAccountAndProfiles(ProfileId activeProfile) {
        try (Connection conn = database.connection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_accounts (player_uuid, active_profile_id, active_switch_operation_id) VALUES (?, NULL, NULL)")) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, profileP1.toString());
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();

                ps.setString(1, profileP2.toString());
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                ps.setString(1, activeProfile.toString());
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_sessions (player_uuid, active_profile_id, authoritative_node, session_epoch, state, lease_expires_at) "
                            + "VALUES (?, ?, ?, 1, 'ACTIVE', datetime('now', '+60 seconds'))")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, activeProfile.toString());
                ps.setString(3, nodeA.value());
                ps.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName(
            "1. Full 2-Phase profile switch lifecycle: PREPARING -> SNAPSHOTTED -> LOADED -> INTENT -> APPLIED -> COMMITTED")
    void fullSwitchLifecycle() {
        UUID opId = UUID.randomUUID();
        byte[] p1Snapshot = new byte[] {1, 2, 3, 4};
        byte[] p2Snapshot = new byte[] {5, 6, 7, 8};

        // 1. Reserve
        Result<ProfileSwitchOperation.Preparing, String> reserveRes =
                adapter.reserveSwitch(opId, playerUuid, profileP1, profileP2, nodeA, 1);
        assertThat(reserveRes.isOk()).isTrue();
        assertThat(reserveRes.orElseThrow().state()).isEqualTo(ProfileSwitchState.PREPARING);

        // Concurrent reservation is rejected
        Result<ProfileSwitchOperation.Preparing, String> concurrentRes =
                adapter.reserveSwitch(UUID.randomUUID(), playerUuid, profileP1, profileP2, nodeA, 1);
        assertThat(concurrentRes.isErr()).isTrue();
        assertThat(concurrentRes.errorOrThrow()).isEqualTo("PROFILE_SWITCH_ALREADY_IN_PROGRESS");

        // 2. Source snapshot
        Result<ProfileSwitchOperation.SourceSnapshotted, String> snapRes =
                adapter.recordSourceSnapshot(opId, p1Snapshot);
        assertThat(snapRes.isOk()).isTrue();
        assertThat(snapRes.orElseThrow().sourceSnapshot()).isEqualTo(p1Snapshot);

        // 3. Target loaded
        Result<ProfileSwitchOperation.TargetLoaded, String> loadedRes = adapter.recordTargetLoaded(opId, p2Snapshot);
        assertThat(loadedRes.isOk()).isTrue();
        assertThat(loadedRes.orElseThrow().targetSnapshot()).isEqualTo(p2Snapshot);

        // 4. Intent commit
        Result<ProfileSwitchOperation.TargetApplyIntent, String> intentRes = adapter.recordTargetApplyIntent(opId);
        assertThat(intentRes.isOk()).isTrue();

        // 5. In-memory apply
        Result<ProfileSwitchOperation.PlayerApplied, String> appliedRes = adapter.recordPlayerApplied(opId);
        assertThat(appliedRes.isOk()).isTrue();

        // 6. Commit
        Result<ProfileSwitchOperation.Committed, String> commitRes =
                adapter.commitSwitch(opId, playerUuid, profileP2, nodeA, 1);
        assertThat(commitRes.isOk()).isTrue();
        assertThat(commitRes.orElseThrow().state()).isEqualTo(ProfileSwitchState.COMMITTED);

        // Verify active_profile_id on session and account are updated to P2
        assertActiveProfileInDb(profileP2);
        assertActiveSwitchClearedInDb();

        // Active operation is now empty
        assertThat(adapter.findActiveOperation(playerUuid)).isEmpty();
    }

    @Test
    @DisplayName("2. Abort switch frees CAS lock and records FAILED audit record")
    void abortSwitchFreesLock() {
        UUID opId = UUID.randomUUID();
        adapter.reserveSwitch(opId, playerUuid, profileP1, profileP2, nodeA, 1);

        Result<Unit, String> abortRes = adapter.abortSwitch(opId, playerUuid, "Simulated client cancel");
        assertThat(abortRes.isOk()).isTrue();

        assertActiveProfileInDb(profileP1); // remains on P1
        assertActiveSwitchClearedInDb();

        Optional<ProfileSwitchOperation> op = adapter.findOperation(opId);
        assertThat(op).isPresent();
        assertThat(op.get().state()).isEqualTo(ProfileSwitchState.FAILED);

        // New switch can be started immediately
        UUID newOpId = UUID.randomUUID();
        Result<ProfileSwitchOperation.Preparing, String> newRes =
                adapter.reserveSwitch(newOpId, playerUuid, profileP1, profileP2, nodeA, 1);
        assertThat(newRes.isOk()).isTrue();
    }

    @Test
    @DisplayName("3. Authority mismatch or stale epoch rejects switch reservation")
    void authorityMismatchRejectsReservation() {
        UUID opId = UUID.randomUUID();
        // Wrong node
        Result<ProfileSwitchOperation.Preparing, String> wrongNode =
                adapter.reserveSwitch(opId, playerUuid, profileP1, profileP2, ServerNodeId.of("node-other"), 1);
        assertThat(wrongNode.isErr()).isTrue();
        assertThat(wrongNode.errorOrThrow()).isEqualTo("AUTHORITY_MISMATCH");

        // Stale epoch
        Result<ProfileSwitchOperation.Preparing, String> staleEpoch =
                adapter.reserveSwitch(opId, playerUuid, profileP1, profileP2, nodeA, 999);
        assertThat(staleEpoch.isErr()).isTrue();
        assertThat(staleEpoch.errorOrThrow()).isEqualTo("AUTHORITY_MISMATCH");
    }

    private void assertActiveProfileInDb(ProfileId expectedProfile) {
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps =
                    conn.prepareStatement("SELECT active_profile_id FROM player_sessions WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("active_profile_id")).isEqualTo(expectedProfile.toString());
                }
            }

            try (PreparedStatement ps =
                    conn.prepareStatement("SELECT active_profile_id FROM player_accounts WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("active_profile_id")).isEqualTo(expectedProfile.toString());
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertActiveSwitchClearedInDb() {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT active_switch_operation_id FROM player_accounts WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("active_switch_operation_id")).isNull();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("A switch to a profile another player owns is refused and reserves nothing")
    void anotherPlayersProfileIsRefused() throws Exception {
        PlayerUuid victim = PlayerUuid.of(UUID.randomUUID());
        ProfileId victimsProfile = ProfileId.of(victim.value());
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, victim.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES (?, ?, 'CLASSIC')")) {
                ps.setString(1, victimsProfile.toString());
                ps.setString(2, victim.toString());
                ps.executeUpdate();
            }
        }

        var refused = adapter.reserveSwitch(UUID.randomUUID(), playerUuid, profileP1, victimsProfile, nodeA, 1);
        var unknown = adapter.reserveSwitch(
                UUID.randomUUID(), playerUuid, profileP1, ProfileId.of(UUID.randomUUID()), nodeA, 1);

        assertThat(refused.isErr()).isTrue();
        assertThat(refused.errorOrThrow()).isEqualTo("TARGET_PROFILE_NOT_OWNED");
        assertThat(unknown.isErr()).describedAs("a profile nobody owns").isTrue();
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT active_switch_operation_id FROM player_accounts WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1))
                        .describedAs("no reservation left behind")
                        .isNull();
            }
        }
        assertThat(adapter.reserveSwitch(UUID.randomUUID(), playerUuid, profileP1, profileP2, nodeA, 1)
                        .isOk())
                .describedAs("the player's own profile still switches")
                .isTrue();
    }
}
