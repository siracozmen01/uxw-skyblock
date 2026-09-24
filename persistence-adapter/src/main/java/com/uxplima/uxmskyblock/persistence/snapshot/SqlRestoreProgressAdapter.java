package com.uxplima.uxmskyblock.persistence.snapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.snapshot.RestoreProgressPort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import org.jspecify.annotations.Nullable;

/**
 * {@code restore_operations} and {@code restore_unit_progress}, as the persistence specification
 * publishes them for the restore unit write-ahead protocol.
 *
 * <p>Every write is its own committed statement, because the point of each one is that it is on disk
 * before the world is touched, or after it was.
 */
public final class SqlRestoreProgressAdapter implements RestoreProgressPort {

    private final DataSource dataSource;

    public SqlRestoreProgressAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void begin(RestoreOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        execute(
                "INSERT INTO restore_operations (restore_id, island_id, snapshot_id, source_prefix, mode, state)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                operation.restoreId().toString(),
                operation.islandId(),
                operation.snapshotId().toString(),
                operation.sourcePrefix(),
                operation.mode().name(),
                OperationState.APPLYING_WORLD.name());
    }

    @Override
    public void intend(UUID restoreId, String unitId, String sourceChecksum) {
        Objects.requireNonNull(restoreId, "restoreId must not be null");
        Objects.requireNonNull(unitId, "unitId must not be null");
        Objects.requireNonNull(sourceChecksum, "sourceChecksum must not be null");
        // A unit a stopped node had already written down is written down again, not added twice.
        int updated = execute(
                "UPDATE restore_unit_progress SET state = 'APPLY_INTENT', source_checksum = ?, completed_at = NULL"
                        + " WHERE restore_id = ? AND unit_id = ?",
                sourceChecksum,
                restoreId.toString(),
                unitId);
        if (updated == 0) {
            execute(
                    "INSERT INTO restore_unit_progress (restore_id, unit_id, state, source_checksum)"
                            + " VALUES (?, ?, 'APPLY_INTENT', ?)",
                    restoreId.toString(),
                    unitId,
                    sourceChecksum);
        }
    }

    @Override
    public void verified(UUID restoreId, String unitId) {
        Objects.requireNonNull(restoreId, "restoreId must not be null");
        Objects.requireNonNull(unitId, "unitId must not be null");
        execute(
                "UPDATE restore_unit_progress SET state = 'VERIFIED', completed_at = CURRENT_TIMESTAMP"
                        + " WHERE restore_id = ? AND unit_id = ?",
                restoreId.toString(),
                unitId);
        execute(
                "UPDATE restore_operations SET last_completed_unit = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE restore_id = ?",
                unitId,
                restoreId.toString());
    }

    @Override
    public Set<String> verifiedUnits(UUID restoreId) {
        Objects.requireNonNull(restoreId, "restoreId must not be null");
        Set<String> units = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT unit_id FROM restore_unit_progress WHERE restore_id = ? AND state = 'VERIFIED'")) {
            statement.setString(1, restoreId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    units.add(rows.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the units of restore " + restoreId, e);
        }
        return units;
    }

    @Override
    public void finish(UUID restoreId, OperationState state, @Nullable String failureReason) {
        Objects.requireNonNull(restoreId, "restoreId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        execute(
                "UPDATE restore_operations SET state = ?, failure_reason = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE restore_id = ?",
                state.name(),
                failureReason,
                restoreId.toString());
    }

    @Override
    public List<RestoreOperation> unfinished() {
        List<RestoreOperation> operations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT restore_id, island_id, snapshot_id, source_prefix, mode FROM restore_operations"
                                + " WHERE state = ? ORDER BY started_at, restore_id")) {
            statement.setString(1, OperationState.APPLYING_WORLD.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    operations.add(new RestoreOperation(
                            UUID.fromString(rows.getString("restore_id")),
                            rows.getString("island_id"),
                            BackupSetId.fromString(rows.getString("snapshot_id")),
                            rows.getString("source_prefix"),
                            RestoreMode.valueOf(rows.getString("mode"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the unfinished restores", e);
        }
        return operations;
    }

    private int execute(String sql, @Nullable String... values) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("A restore could not write down its progress", e);
        }
    }
}
