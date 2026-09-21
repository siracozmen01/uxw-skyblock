package com.uxplima.uxmskyblock.persistence.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import org.jspecify.annotations.Nullable;

/**
 * Who is holding a vault page, and what happens when they let go.
 *
 * <p>Split out of the storage adapter with the escrow journal, because the three answered different
 * questions in one file: what is in a page, who is holding it, and what was attempted. This is the
 * lease: acquiring it, committing under it, aborting it, and finding the ones that ran out.
 */
public final class SqlVaultSessionStore {

    private final Database database;

    public SqlVaultSessionStore(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    public Optional<VaultEditSession> acquireEditSession(
            IslandId islandId, int page, UUID playerUuid, Duration leaseDuration) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");

        String selectPageSql = """
                SELECT page_version, lease_epoch, active_session_id
                FROM island_vault_pages
                WHERE island_id = ? AND page = ?
                """;

        String checkSessionSql = """
                SELECT state, expires_at
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        String abortSessionSql = """
                UPDATE vault_edit_sessions
                SET state = 'ABORTED', closed_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state = 'ACTIVE'
                """;

        String updatePageSql = """
                UPDATE island_vault_pages
                SET lease_epoch = ?, active_session_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ? AND page = ? AND lease_epoch = ?
                """;

        String insertSessionSql = """
                INSERT INTO vault_edit_sessions (
                    session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, opened_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long currentVersion;
                long currentEpoch;
                String activeSessionStr = null;

                try (PreparedStatement selectStmt = conn.prepareStatement(selectPageSql)) {
                    selectStmt.setString(1, islandId.value().toString());
                    selectStmt.setInt(2, page);
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return Optional.empty();
                        }
                        currentVersion = rs.getLong("page_version");
                        currentEpoch = rs.getLong("lease_epoch");
                        activeSessionStr = rs.getString("active_session_id");
                    }
                }

                Instant now = Instant.now();

                // If currently locked, check whether active session is expired
                if (activeSessionStr != null) {
                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSessionSql)) {
                        checkStmt.setString(1, activeSessionStr);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                String state = rs.getString("state");
                                Timestamp expTs = rs.getTimestamp("expires_at");
                                Instant expiresAt = expTs.toInstant();
                                if ("ACTIVE".equals(state) && expiresAt.isAfter(now)) {
                                    // Session is still active and valid - locked!
                                    conn.rollback();
                                    return Optional.empty();
                                }
                            }
                        }
                    }

                    // Previous session is expired or not active; mark it ABORTED
                    try (PreparedStatement abortStmt = conn.prepareStatement(abortSessionSql)) {
                        abortStmt.setString(1, activeSessionStr);
                        abortStmt.executeUpdate();
                    }
                }

                // Acquire lease: increment epoch, bind session
                VaultSessionId newSessionId = VaultSessionId.random();
                long nextEpoch = currentEpoch + 1;
                Instant expiresAt = now.plus(leaseDuration);

                int updatedRows;
                try (PreparedStatement updateStmt = conn.prepareStatement(updatePageSql)) {
                    updateStmt.setLong(1, nextEpoch);
                    updateStmt.setString(2, newSessionId.toString());
                    updateStmt.setString(3, islandId.value().toString());
                    updateStmt.setInt(4, page);
                    updateStmt.setLong(5, currentEpoch);
                    updatedRows = updateStmt.executeUpdate();
                }

                if (updatedRows == 0) {
                    // Concurrent lease acquisition race condition
                    conn.rollback();
                    return Optional.empty();
                }

                try (PreparedStatement insertStmt = conn.prepareStatement(insertSessionSql)) {
                    insertStmt.setString(1, newSessionId.toString());
                    insertStmt.setString(2, islandId.value().toString());
                    insertStmt.setInt(3, page);
                    insertStmt.setString(4, playerUuid.toString());
                    insertStmt.setLong(5, nextEpoch);
                    insertStmt.setLong(6, currentVersion);
                    insertStmt.setTimestamp(7, Timestamp.from(now));
                    insertStmt.setTimestamp(8, Timestamp.from(expiresAt));
                    insertStmt.executeUpdate();
                }

                conn.commit();

                VaultEditSession session = new VaultEditSession(
                        newSessionId,
                        islandId,
                        page,
                        playerUuid,
                        nextEpoch,
                        currentVersion,
                        VaultSessionState.ACTIVE,
                        null,
                        now,
                        expiresAt,
                        null);
                return Optional.of(session);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire edit session for island " + islandId + " page " + page, e);
        }
    }

    public Optional<VaultEditSession> findSession(VaultSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        String sql = """
                SELECT session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, escrow_journal, opened_at, expires_at, closed_at
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVaultEditSession(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find vault session " + sessionId, e);
        }
    }

    public boolean commitEditSession(
            VaultSessionId sessionId,
            byte[] newContentsNbt,
            String modifiedBy,
            byte @Nullable [] playerInventoryNbt,
            @Nullable ProfileId playerProfileId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(newContentsNbt, "newContentsNbt must not be null");
        Objects.requireNonNull(modifiedBy, "modifiedBy must not be null");

        String selectSessionSql = """
                SELECT island_id, page, lease_epoch, base_page_version, state, expires_at
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        String updatePageSql = """
                UPDATE island_vault_pages
                SET contents_nbt = ?,
                    page_version = page_version + 1,
                    active_session_id = NULL,
                    last_modified_by = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ?
                  AND page = ?
                  AND page_version = ?
                  AND lease_epoch = ?
                  AND active_session_id = ?
                """;

        String updateProfileInvSql = """
                UPDATE profile_inventories
                SET inventory_nbt = ?,
                    profile_inventory_version = profile_inventory_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE profile_id = ?
                """;

        String commitSessionSql = """
                UPDATE vault_edit_sessions
                SET state = 'COMMITTED',
                    closed_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state = 'ACTIVE'
                """;

        String commitTransfersSql = """
                UPDATE vault_escrow_transfers
                SET state = 'COMMITTED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state IN ('INTENT', 'APPLYING', 'APPLIED')
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                String islandIdStr;
                int page;
                long leaseEpoch;
                long basePageVersion;
                String state;
                Timestamp expTs;

                try (PreparedStatement selectStmt = conn.prepareStatement(selectSessionSql)) {
                    selectStmt.setString(1, sessionId.toString());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        islandIdStr = rs.getString("island_id");
                        page = rs.getInt("page");
                        leaseEpoch = rs.getLong("lease_epoch");
                        basePageVersion = rs.getLong("base_page_version");
                        state = rs.getString("state");
                        expTs = rs.getTimestamp("expires_at");
                    }
                }

                if (!"ACTIVE".equals(state) || expTs.toInstant().isBefore(Instant.now())) {
                    conn.rollback();
                    return false;
                }

                // 1. Atomic CAS update on island_vault_pages
                int updatedPageRows;
                try (PreparedStatement updatePageStmt = conn.prepareStatement(updatePageSql)) {
                    updatePageStmt.setBytes(1, newContentsNbt);
                    updatePageStmt.setString(2, modifiedBy);
                    updatePageStmt.setString(3, islandIdStr);
                    updatePageStmt.setInt(4, page);
                    updatePageStmt.setLong(5, basePageVersion);
                    updatePageStmt.setLong(6, leaseEpoch);
                    updatePageStmt.setString(7, sessionId.toString());
                    updatedPageRows = updatePageStmt.executeUpdate();
                }

                if (updatedPageRows == 0) {
                    conn.rollback();
                    return false;
                }

                // 2. Coordinated update of player inventory if supplied
                if (playerProfileId != null && playerInventoryNbt != null) {
                    try (PreparedStatement invStmt = conn.prepareStatement(updateProfileInvSql)) {
                        invStmt.setBytes(1, playerInventoryNbt);
                        invStmt.setString(2, playerProfileId.value().toString());
                        int invRows = invStmt.executeUpdate();
                        if (invRows == 0) {
                            conn.rollback();
                            return false;
                        }
                    }
                }

                // 3. Mark session COMMITTED
                int sessionRows;
                try (PreparedStatement commitSessionStmt = conn.prepareStatement(commitSessionSql)) {
                    commitSessionStmt.setString(1, sessionId.toString());
                    sessionRows = commitSessionStmt.executeUpdate();
                }

                if (sessionRows == 0) {
                    conn.rollback();
                    return false;
                }

                // 4. Finalize associated escrow transfers
                try (PreparedStatement transfersStmt = conn.prepareStatement(commitTransfersSql)) {
                    transfersStmt.setString(1, sessionId.toString());
                    transfersStmt.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to commit vault edit session " + sessionId, e);
        }
    }

    public boolean abortEditSession(VaultSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        String selectSessionSql = """
                SELECT island_id, page
                FROM vault_edit_sessions
                WHERE session_id = ?
                """;

        String abortSessionSql = """
                UPDATE vault_edit_sessions
                SET state = 'ABORTED',
                    closed_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state = 'ACTIVE'
                """;

        String unlockPageSql = """
                UPDATE island_vault_pages
                SET active_session_id = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE island_id = ? AND page = ? AND active_session_id = ?
                """;

        String abortTransfersSql = """
                UPDATE vault_escrow_transfers
                SET state = 'ABORTED',
                    updated_at = CURRENT_TIMESTAMP
                WHERE session_id = ? AND state NOT IN ('COMMITTED', 'ABORTED', 'RECOVERY_REQUIRED')
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                String islandId;
                int page;

                try (PreparedStatement selectStmt = conn.prepareStatement(selectSessionSql)) {
                    selectStmt.setString(1, sessionId.toString());
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                        islandId = rs.getString("island_id");
                        page = rs.getInt("page");
                    }
                }

                try (PreparedStatement abortStmt = conn.prepareStatement(abortSessionSql)) {
                    abortStmt.setString(1, sessionId.toString());
                    abortStmt.executeUpdate();
                }

                try (PreparedStatement unlockStmt = conn.prepareStatement(unlockPageSql)) {
                    unlockStmt.setString(1, islandId);
                    unlockStmt.setInt(2, page);
                    unlockStmt.setString(3, sessionId.toString());
                    unlockStmt.executeUpdate();
                }

                try (PreparedStatement transfersStmt = conn.prepareStatement(abortTransfersSql)) {
                    transfersStmt.setString(1, sessionId.toString());
                    transfersStmt.executeUpdate();
                }

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to abort vault edit session " + sessionId, e);
        }
    }

    public List<VaultEditSession> findExpiredActiveSessions() {
        String sql = """
                SELECT session_id, island_id, page, player_uuid, lease_epoch, base_page_version, state, escrow_journal, opened_at, expires_at, closed_at
                FROM vault_edit_sessions
                WHERE state = 'ACTIVE' AND expires_at < CURRENT_TIMESTAMP
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            List<VaultEditSession> list = new ArrayList<>();
            while (rs.next()) {
                list.add(mapVaultEditSession(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query expired active vault edit sessions", e);
        }
    }

    private static VaultEditSession mapVaultEditSession(ResultSet rs) throws SQLException {
        Timestamp closedTs = rs.getTimestamp("closed_at");
        Instant closedAt = closedTs != null ? closedTs.toInstant() : null;
        return new VaultEditSession(
                VaultSessionId.fromString(rs.getString("session_id")),
                IslandId.fromString(rs.getString("island_id")),
                rs.getInt("page"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("lease_epoch"),
                rs.getLong("base_page_version"),
                VaultSessionState.valueOf(rs.getString("state")),
                rs.getString("escrow_journal"),
                rs.getTimestamp("opened_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                closedAt);
    }
}
