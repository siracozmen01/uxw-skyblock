package com.uxplima.uxmskyblock.persistence.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import org.jspecify.annotations.Nullable;

/**
 * SQL persistence adapter for {@link IslandVaultStoragePort} implementing relational persistence,
 * pessimistic page lease acquisition, atomic CAS commit transactions, write-ahead escrow transfers,
 * and rolling audit history across SQLite, MySQL, and PostgreSQL.
 */
public final class SqlIslandVaultStorageAdapter implements IslandVaultStoragePort {

    private final Database database;
    private final SqlVaultEscrowJournal escrowJournal;
    private final SqlVaultSessionStore sessions;

    public SqlIslandVaultStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.escrowJournal = new SqlVaultEscrowJournal(database);
        this.sessions = new SqlVaultSessionStore(database);
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
        return sessions.acquireEditSession(islandId, page, playerUuid, leaseDuration);
    }

    @Override
    public Optional<VaultEditSession> findSession(VaultSessionId sessionId) {
        return sessions.findSession(sessionId);
    }

    @Override
    public boolean commitEditSession(
            VaultSessionId sessionId,
            byte[] newContentsNbt,
            String modifiedBy,
            byte @Nullable [] playerInventoryNbt,
            @Nullable ProfileId playerProfileId) {
        return sessions.commitEditSession(sessionId, newContentsNbt, modifiedBy, playerInventoryNbt, playerProfileId);
    }

    @Override
    public boolean abortEditSession(VaultSessionId sessionId) {
        return sessions.abortEditSession(sessionId);
    }

    @Override
    public List<VaultEditSession> findExpiredActiveSessions() {
        return sessions.findExpiredActiveSessions();
    }

    @Override
    public void recordEscrowTransfer(EscrowTransferRecord transfer) {
        escrowJournal.recordEscrowTransfer(transfer);
    }

    @Override
    public void updateEscrowTransferState(UUID transferId, EscrowTransferState state) {
        escrowJournal.updateEscrowTransferState(transferId, state);
    }

    @Override
    public List<EscrowTransferRecord> findEscrowTransfers(VaultSessionId sessionId) {
        return escrowJournal.findEscrowTransfers(sessionId);
    }

    @Override
    public void appendAuditLog(VaultAuditLogEntry entry) {
        escrowJournal.appendAuditLog(entry);
    }

    @Override
    public List<VaultAuditLogEntry> findRecentAuditLogs(IslandId islandId, int limit) {
        return escrowJournal.findRecentAuditLogs(islandId, limit);
    }

    @Override
    public void appendAuditLogs(List<VaultAuditLogEntry> entries) {
        escrowJournal.appendAuditLogs(entries);
    }

    @Override
    public int trimAuditLogs(int keepPerPage) {
        return escrowJournal.trimAuditLogs(keepPerPage);
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
}
