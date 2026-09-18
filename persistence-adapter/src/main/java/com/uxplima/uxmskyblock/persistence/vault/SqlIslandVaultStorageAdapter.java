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
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.TransferSourceType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import org.jspecify.annotations.Nullable;

/**
 * SQL persistence adapter for {@link IslandVaultStoragePort} implementing relational persistence,
 * pessimistic page lease acquisition, atomic CAS commit transactions, write-ahead escrow transfers,
 * and rolling audit history across SQLite, MySQL, and PostgreSQL.
 */
public final class SqlIslandVaultStorageAdapter implements IslandVaultStoragePort {

    private final Database database;

    public SqlIslandVaultStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public Optional<VaultPage> findPage(IslandId islandId, int page) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = """
                SELECT island_id, page, page_version, lease_epoch, active_session_id, contents_nbt, last_modified_by, updated_at
                FROM island_vault_pages
                WHERE island_id = ? AND page = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setInt(2, page);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVaultPage(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find vault page " + page + " for island " + islandId, e);
        }
    }

    @Override
    public List<VaultPage> findAllPages(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = """
                SELECT island_id, page, page_version, lease_epoch, active_session_id, contents_nbt, last_modified_by, updated_at
                FROM island_vault_pages
                WHERE island_id = ?
                ORDER BY page ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<VaultPage> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapVaultPage(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find all vault pages for island " + islandId, e);
        }
    }

    @Override
    public VaultPage createPage(IslandId islandId, int page, byte[] initialContentsNbt, String createdBy) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(initialContentsNbt, "initialContentsNbt must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");

        String sql = """
                INSERT INTO island_vault_pages (
                    island_id, page, page_version, lease_epoch, active_session_id, contents_nbt, last_modified_by, updated_at
                ) VALUES (?, ?, 1, 1, NULL, ?, ?, CURRENT_TIMESTAMP)
                """;

        Instant now = Instant.now();
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setInt(2, page);
            stmt.setBytes(3, initialContentsNbt);
            stmt.setString(4, createdBy);
            stmt.executeUpdate();
            return new VaultPage(islandId, page, 1L, 1L, null, initialContentsNbt, createdBy, now);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create vault page " + page + " for island " + islandId, e);
        }
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
    public void recordEscrowTransfer(EscrowTransferRecord transfer) {
        Objects.requireNonNull(transfer, "transfer must not be null");

        String sql = """
                INSERT INTO vault_escrow_transfers (
                    transfer_id, session_id, source_type, dest_type, source_slot, dest_slot,
                    source_before_fp, source_after_fp, dest_before_fp, dest_after_fp,
                    source_expected_version, dest_expected_version, source_container_version, dest_container_version,
                    item_nbt, quantity, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, transfer.transferId().toString());
            stmt.setString(2, transfer.sessionId().toString());
            stmt.setString(3, transfer.source().name());
            stmt.setString(4, transfer.destination().name());
            stmt.setInt(5, transfer.sourceSlot());
            stmt.setInt(6, transfer.destinationSlot());
            stmt.setString(7, transfer.sourceBeforeFingerprint());
            stmt.setString(8, transfer.sourceAfterFingerprint());
            stmt.setString(9, transfer.destinationBeforeFingerprint());
            stmt.setString(10, transfer.destinationAfterFingerprint());
            stmt.setLong(11, transfer.sourceExpectedVersion());
            stmt.setLong(12, transfer.destinationExpectedVersion());
            stmt.setLong(13, transfer.sourceContainerVersion());
            stmt.setLong(14, transfer.destinationContainerVersion());
            stmt.setBytes(15, transfer.serializedItemNbt());
            stmt.setInt(16, transfer.quantity());
            stmt.setString(17, transfer.state().name());
            stmt.setTimestamp(18, Timestamp.from(transfer.createdAt()));
            stmt.setTimestamp(19, Timestamp.from(transfer.updatedAt()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record escrow transfer " + transfer.transferId(), e);
        }
    }

    @Override
    public void updateEscrowTransferState(UUID transferId, EscrowTransferState state) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        Objects.requireNonNull(state, "state must not be null");

        String sql = """
                UPDATE vault_escrow_transfers
                SET state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE transfer_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, state.name());
            stmt.setString(2, transferId.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update escrow transfer state for " + transferId, e);
        }
    }

    @Override
    public List<EscrowTransferRecord> findEscrowTransfers(VaultSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");

        String sql = """
                SELECT transfer_id, session_id, source_type, dest_type, source_slot, dest_slot,
                       source_before_fp, source_after_fp, dest_before_fp, dest_after_fp,
                       source_expected_version, dest_expected_version, source_container_version, dest_container_version,
                       item_nbt, quantity, state, created_at, updated_at
                FROM vault_escrow_transfers
                WHERE session_id = ?
                ORDER BY created_at ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sessionId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<EscrowTransferRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapEscrowTransfer(rs));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find escrow transfers for session " + sessionId, e);
        }
    }

    @Override
    public void appendAuditLog(VaultAuditLogEntry logEntry) {
        Objects.requireNonNull(logEntry, "logEntry must not be null");

        String sql = """
                INSERT INTO vault_audit_logs (
                    log_id, island_id, page, actor_profile_id, action_type, slot, item_summary, quantity, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, logEntry.logId().toString());
            stmt.setString(2, logEntry.islandId().value().toString());
            stmt.setInt(3, logEntry.page());
            stmt.setString(4, logEntry.actorProfileId());
            stmt.setString(5, logEntry.actionType().name());
            stmt.setInt(6, logEntry.slot());
            stmt.setString(7, logEntry.itemSummary());
            stmt.setInt(8, logEntry.quantity());
            stmt.setTimestamp(9, Timestamp.from(logEntry.createdAt()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to append vault audit log " + logEntry.logId(), e);
        }
    }

    @Override
    public List<VaultAuditLogEntry> findRecentAuditLogs(IslandId islandId, int limit) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT log_id, island_id, page, actor_profile_id, action_type, slot, item_summary, quantity, created_at
                FROM vault_audit_logs
                WHERE island_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setInt(2, Math.max(1, limit));
            try (ResultSet rs = stmt.executeQuery()) {
                List<VaultAuditLogEntry> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new VaultAuditLogEntry(
                            UUID.fromString(rs.getString("log_id")),
                            IslandId.fromString(rs.getString("island_id")),
                            rs.getInt("page"),
                            rs.getString("actor_profile_id"),
                            VaultActionType.valueOf(rs.getString("action_type")),
                            rs.getInt("slot"),
                            rs.getString("item_summary"),
                            rs.getInt("quantity"),
                            rs.getTimestamp("created_at").toInstant()));
                }
                return list;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find recent audit logs for island " + islandId, e);
        }
    }

    @Override
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

    private static VaultPage mapVaultPage(ResultSet rs) throws SQLException {
        String activeSessionStr = rs.getString("active_session_id");
        VaultSessionId activeSessionId = activeSessionStr != null ? VaultSessionId.fromString(activeSessionStr) : null;
        return new VaultPage(
                IslandId.fromString(rs.getString("island_id")),
                rs.getInt("page"),
                rs.getLong("page_version"),
                rs.getLong("lease_epoch"),
                activeSessionId,
                rs.getBytes("contents_nbt"),
                rs.getString("last_modified_by"),
                rs.getTimestamp("updated_at").toInstant());
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

    private static EscrowTransferRecord mapEscrowTransfer(ResultSet rs) throws SQLException {
        return new EscrowTransferRecord(
                UUID.fromString(rs.getString("transfer_id")),
                VaultSessionId.fromString(rs.getString("session_id")),
                TransferSourceType.valueOf(rs.getString("source_type")),
                TransferSourceType.valueOf(rs.getString("dest_type")),
                rs.getInt("source_slot"),
                rs.getInt("dest_slot"),
                rs.getString("source_before_fp"),
                rs.getString("source_after_fp"),
                rs.getString("dest_before_fp"),
                rs.getString("dest_after_fp"),
                rs.getLong("source_expected_version"),
                rs.getLong("dest_expected_version"),
                rs.getLong("source_container_version"),
                rs.getLong("dest_container_version"),
                rs.getBytes("item_nbt"),
                rs.getInt("quantity"),
                EscrowTransferState.valueOf(rs.getString("state")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
