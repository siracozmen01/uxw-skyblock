package com.uxplima.uxmskyblock.persistence.backup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.backup.BackupCatalogPort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupLifecycleState;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.backup.BackupType;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL persistence adapter for the Backup Catalog.
 */
public final class PlayerBackupCatalogAdapter implements BackupCatalogPort {

    private final Database database;
    private final Dialect dialect;

    public PlayerBackupCatalogAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        SupportedDialects.require(dialect, "backup catalog persistence");
    }

    @Override
    public void save(BackupCatalogRecord record) {
        Objects.requireNonNull(record, "record");

        String sql =
                switch (dialect) {
                    case SQLITE, POSTGRES -> """
                    INSERT INTO backup_operations (
                        backup_set_id, backup_type, target_root_type_id, target_root_key,
                        state, authority_epoch, db_version, schema_version, plugin_version,
                        failure_reason, created_at, completed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (backup_set_id) DO UPDATE SET
                        backup_type = excluded.backup_type,
                        target_root_type_id = excluded.target_root_type_id,
                        target_root_key = excluded.target_root_key,
                        state = excluded.state,
                        authority_epoch = excluded.authority_epoch,
                        db_version = excluded.db_version,
                        schema_version = excluded.schema_version,
                        plugin_version = excluded.plugin_version,
                        failure_reason = excluded.failure_reason,
                        completed_at = excluded.completed_at,
                        updated_at = excluded.updated_at
                    """;
                    case MYSQL -> """
                    INSERT INTO backup_operations (
                        backup_set_id, backup_type, target_root_type_id, target_root_key,
                        state, authority_epoch, db_version, schema_version, plugin_version,
                        failure_reason, created_at, completed_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        backup_type = VALUES(backup_type),
                        target_root_type_id = VALUES(target_root_type_id),
                        target_root_key = VALUES(target_root_key),
                        state = VALUES(state),
                        authority_epoch = VALUES(authority_epoch),
                        db_version = VALUES(db_version),
                        schema_version = VALUES(schema_version),
                        plugin_version = VALUES(plugin_version),
                        failure_reason = VALUES(failure_reason),
                        completed_at = VALUES(completed_at),
                        updated_at = VALUES(updated_at)
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported dialect: " + dialect);
                };

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, record.backupSetId().value().toString());
            ps.setString(2, record.backupType().name());
            ps.setString(3, record.targetRootTypeId());
            ps.setString(4, record.targetRootKey());
            ps.setString(5, record.state().name());
            ps.setLong(6, record.authorityEpoch());
            ps.setLong(7, record.dbVersion());
            ps.setInt(8, record.schemaVersion());
            ps.setString(9, record.pluginVersion());
            ps.setString(10, record.failureReason());
            ps.setTimestamp(11, Timestamp.from(record.createdAt()));
            if (record.completedAt() != null) {
                ps.setTimestamp(12, Timestamp.from(record.completedAt()));
            } else {
                ps.setNull(12, java.sql.Types.TIMESTAMP);
            }
            ps.setTimestamp(13, Timestamp.from(record.updatedAt()));

            ps.executeUpdate();
        } catch (SQLException e) {
            throw new BackupCatalogPersistenceException(
                    "Failed to save backup catalog record: " + record.backupSetId(), e);
        }
    }

    @Override
    public Optional<BackupCatalogRecord> findById(BackupSetId id) {
        Objects.requireNonNull(id, "id");

        String sql = """
                SELECT backup_set_id, backup_type, target_root_type_id, target_root_key,
                       state, authority_epoch, db_version, schema_version, plugin_version,
                       failure_reason, created_at, completed_at, updated_at
                FROM backup_operations
                WHERE backup_set_id = ?
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRecord(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new BackupCatalogPersistenceException("Failed to find backup catalog record by id: " + id, e);
        }
    }

    @Override
    public List<BackupCatalogRecord> findByRoot(String rootTypeId, String rootKey) {
        Objects.requireNonNull(rootTypeId, "rootTypeId");
        Objects.requireNonNull(rootKey, "rootKey");

        String sql = """
                SELECT backup_set_id, backup_type, target_root_type_id, target_root_key,
                       state, authority_epoch, db_version, schema_version, plugin_version,
                       failure_reason, created_at, completed_at, updated_at
                FROM backup_operations
                WHERE target_root_type_id = ? AND target_root_key = ?
                ORDER BY created_at DESC
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rootTypeId);
            ps.setString(2, rootKey);
            try (ResultSet rs = ps.executeQuery()) {
                List<BackupCatalogRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRecord(rs));
                }
                return Collections.unmodifiableList(list);
            }
        } catch (SQLException e) {
            throw new BackupCatalogPersistenceException(
                    "Failed to find backup catalog records for root: " + rootTypeId + ":" + rootKey, e);
        }
    }

    @Override
    public void updateState(BackupSetId id, BackupLifecycleState state, @Nullable String failureReason) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        boolean isTerminal = state == BackupLifecycleState.AVAILABLE
                || state == BackupLifecycleState.FAILED
                || state == BackupLifecycleState.DELETED;

        String sql = """
                UPDATE backup_operations SET
                    state = ?,
                    failure_reason = ?,
                    completed_at = CASE WHEN ? = 1 THEN COALESCE(completed_at, ?) ELSE completed_at END,
                    updated_at = ?
                WHERE backup_set_id = ?
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, state.name());
            ps.setString(2, failureReason);
            ps.setInt(3, isTerminal ? 1 : 0);
            ps.setTimestamp(4, nowTs);
            ps.setTimestamp(5, nowTs);
            ps.setString(6, id.value().toString());

            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new BackupCatalogPersistenceException(
                        "No backup catalog record found to update state for: " + id);
            }
        } catch (SQLException e) {
            throw new BackupCatalogPersistenceException("Failed to update state for backup record: " + id, e);
        }
    }

    private static BackupCatalogRecord mapRecord(ResultSet rs) throws SQLException {
        BackupSetId backupSetId = BackupSetId.fromString(rs.getString("backup_set_id"));
        BackupType backupType = BackupType.valueOf(rs.getString("backup_type"));
        String targetRootTypeId = rs.getString("target_root_type_id");
        String targetRootKey = rs.getString("target_root_key");
        BackupLifecycleState state = BackupLifecycleState.valueOf(rs.getString("state"));
        long authorityEpoch = rs.getLong("authority_epoch");
        long dbVersion = rs.getLong("db_version");
        int schemaVersion = rs.getInt("schema_version");
        String pluginVersion = rs.getString("plugin_version");
        String failureReason = rs.getString("failure_reason");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp completedTs = rs.getTimestamp("completed_at");
        Instant completedAt = completedTs != null ? completedTs.toInstant() : null;
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        return new BackupCatalogRecord(
                backupSetId,
                backupType,
                targetRootTypeId,
                targetRootKey,
                state,
                authorityEpoch,
                dbVersion,
                schemaVersion,
                pluginVersion,
                failureReason,
                createdAt,
                completedAt,
                updatedAt);
    }
}
