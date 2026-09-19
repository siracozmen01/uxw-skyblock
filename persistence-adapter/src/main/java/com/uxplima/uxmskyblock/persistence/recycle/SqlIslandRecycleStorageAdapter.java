package com.uxplima.uxmskyblock.persistence.recycle;

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

import com.uxplima.uxmlib.storage.StorageException;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleOperationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleOperation;
import com.uxplima.uxmskyblock.core.domain.recycle.IslandRecycleState;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL implementation of {@link IslandRecycleOperationPort} persisting
 * island recycle operations across SQLite, MariaDB, and PostgreSQL.
 */
public final class SqlIslandRecycleStorageAdapter implements IslandRecycleOperationPort {

    private final Database database;

    public SqlIslandRecycleStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void recordOperation(IslandRecycleOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");

        String sql = """
                INSERT INTO island_recycle_operations
                    (operation_id, island_id, initiator_uuid, target_slot, state, backup_path, error_message, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, operation.operationId());
            ps.setString(2, operation.islandId().value().toString());
            ps.setString(3, operation.initiatorUuid().value().toString());
            ps.setLong(4, operation.targetSlot());
            ps.setString(5, operation.state().name());
            ps.setString(6, operation.backupPath());
            ps.setString(7, operation.errorMessage());
            ps.setTimestamp(8, Timestamp.from(operation.createdAt()));
            ps.setTimestamp(9, Timestamp.from(operation.updatedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to record island recycle operation: " + operation.operationId(), e);
        }
    }

    @Override
    public void updateState(
            String operationId,
            IslandRecycleState newState,
            @Nullable String backupPath,
            @Nullable String errorMessage,
            Instant updatedAt) {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        String sql = """
                UPDATE island_recycle_operations
                SET state = ?,
                    backup_path = COALESCE(?, backup_path),
                    error_message = COALESCE(?, error_message),
                    updated_at = ?
                WHERE operation_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newState.name());
            ps.setString(2, backupPath);
            ps.setString(3, errorMessage);
            ps.setTimestamp(4, Timestamp.from(updatedAt));
            ps.setString(5, operationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to update island recycle operation: " + operationId, e);
        }
    }

    @Override
    public Optional<IslandRecycleOperation> findOperationById(String operationId) {
        Objects.requireNonNull(operationId, "operationId must not be null");

        String sql = """
                SELECT operation_id, island_id, initiator_uuid, target_slot, state, backup_path, error_message, created_at, updated_at
                FROM island_recycle_operations
                WHERE operation_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, operationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to find island recycle operation by id: " + operationId, e);
        }
    }

    @Override
    public List<IslandRecycleOperation> findOperationsByIslandId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT operation_id, island_id, initiator_uuid, target_slot, state, backup_path, error_message, created_at, updated_at
                FROM island_recycle_operations
                WHERE island_id = ?
                ORDER BY created_at DESC
                """;

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<IslandRecycleOperation> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return Collections.unmodifiableList(list);
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to find island recycle operations for island: " + islandId, e);
        }
    }

    private static IslandRecycleOperation mapRow(ResultSet rs) throws SQLException {
        String opId = rs.getString("operation_id");
        IslandId islandId = IslandId.fromString(rs.getString("island_id"));
        PlayerUuid initiator = PlayerUuid.fromString(rs.getString("initiator_uuid"));
        long slot = rs.getLong("target_slot");
        IslandRecycleState state = IslandRecycleState.valueOf(rs.getString("state"));
        String backupPath = rs.getString("backup_path");
        String errorMessage = rs.getString("error_message");
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp updatedTs = rs.getTimestamp("updated_at");

        Instant createdAt = createdTs != null ? createdTs.toInstant() : Instant.now();
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : Instant.now();

        return new IslandRecycleOperation(
                opId, islandId, initiator, slot, state, backupPath, errorMessage, createdAt, updatedAt);
    }
}
