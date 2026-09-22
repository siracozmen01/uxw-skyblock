package com.uxplima.uxmskyblock.persistence.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.TransferSourceType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;

/**
 * What a vault edit attempted and what happened to it.
 *
 * <p>Split out of the storage adapter, which had grown to answer three different questions at once:
 * what is in a page, who is holding it, and this. The escrow rows and the audit rows are the only
 * part of the vault that is written to be read afterwards rather than acted on, so they sit apart.
 */
public final class SqlVaultEscrowJournal {

    private final Database database;

    public SqlVaultEscrowJournal(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

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

    private static final String INSERT_AUDIT_LOG_SQL = """
            INSERT INTO vault_audit_logs (
                log_id, island_id, page, actor_profile_id, action_type, slot, item_summary, quantity, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    public void appendAuditLog(VaultAuditLogEntry logEntry) {
        Objects.requireNonNull(logEntry, "logEntry must not be null");
        appendAuditLogs(List.of(logEntry));
    }

    /**
     * Writes a whole commit's audit entries down one connection.
     *
     * <p>One chest edit moves several slots, and a connection for each of them is a round trip for
     * each of them.
     */
    public void appendAuditLogs(List<VaultAuditLogEntry> entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            return;
        }

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(INSERT_AUDIT_LOG_SQL)) {
            for (VaultAuditLogEntry logEntry : entries) {
                stmt.setString(1, logEntry.logId().toString());
                stmt.setString(2, logEntry.islandId().value().toString());
                stmt.setInt(3, logEntry.page());
                stmt.setString(4, logEntry.actorProfileId());
                stmt.setString(5, logEntry.actionType().name());
                stmt.setInt(6, logEntry.slot());
                stmt.setString(7, logEntry.itemSummary());
                stmt.setInt(8, logEntry.quantity());
                stmt.setTimestamp(9, Timestamp.from(logEntry.createdAt()));
                stmt.addBatch();
            }
            stmt.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to append " + entries.size() + " vault audit log entries", e);
        }
    }

    /**
     * Keeps the newest {@code keepPerPage} entries of every page and deletes the rest.
     *
     * <p>One statement, ranked inside a derived table, because MariaDB refuses a bare subquery over
     * the table a DELETE is working on.
     */
    public int trimAuditLogs(int keepPerPage) {
        if (keepPerPage < 1) {
            throw new IllegalArgumentException("keepPerPage must be >= 1: " + keepPerPage);
        }

        String sql = """
                DELETE FROM vault_audit_logs
                WHERE log_id IN (
                    SELECT log_id FROM (
                        SELECT log_id, ROW_NUMBER() OVER (
                            PARTITION BY island_id, page ORDER BY created_at DESC, log_id DESC
                        ) AS rank_in_page
                        FROM vault_audit_logs
                    ) ranked
                    WHERE rank_in_page > ?
                )
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, keepPerPage);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to trim vault audit logs to " + keepPerPage + " per page", e);
        }
    }

    public List<VaultAuditLogEntry> findRecentAuditLogs(IslandId islandId, int limit) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT log_id, island_id, page, actor_profile_id, action_type, slot, item_summary, quantity, created_at
                FROM vault_audit_logs
                WHERE island_id = ?
                ORDER BY created_at DESC, log_id DESC
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
