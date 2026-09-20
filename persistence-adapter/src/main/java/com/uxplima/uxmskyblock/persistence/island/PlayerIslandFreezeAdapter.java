package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezePort;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL persistence adapter implementing {@link IslandAdminFreezePort}.
 */
public final class PlayerIslandFreezeAdapter implements IslandAdminFreezePort {

    private final Database database;
    private final Dialect dialect;

    public PlayerIslandFreezeAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        IslandSqlSupport.validateDialect(this.dialect);
    }

    @Override
    public void updateAdministrativeState(IslandId islandId, AdministrativeState state, @Nullable String freezeReason) {
        updateAdministrativeState(islandId, state, freezeReason, null);
    }

    @Override
    public void updateAdministrativeState(
            IslandId islandId,
            AdministrativeState state,
            @Nullable String freezeReason,
            @Nullable StagedOutboxEvent outboxEvent) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(state, "state");
        String sql =
                "UPDATE islands SET administrative_state = ?, freeze_reason = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, state.name());
                    stmt.setString(2, freezeReason);
                    stmt.setString(3, islandId.value().toString());
                    stmt.executeUpdate();
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
            } catch (Exception e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                throw new IslandPersistenceException(
                        "Failed to update administrative state for island: " + islandId, e);
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to update administrative state for island: " + islandId, e);
        }
    }

    @Override
    public void updateEconomicState(IslandId islandId, EconomicState state) {
        updateEconomicState(islandId, state, null);
    }

    @Override
    public void updateEconomicState(IslandId islandId, EconomicState state, @Nullable StagedOutboxEvent outboxEvent) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(state, "state");
        String sql = "UPDATE islands SET economic_state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = database.connection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, state.name());
                    stmt.setString(2, islandId.value().toString());
                    stmt.executeUpdate();
                }
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(conn, outboxEvent);
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
            } catch (Exception e) {
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                throw new IslandPersistenceException("Failed to update economic state for island: " + islandId, e);
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to update economic state for island: " + islandId, e);
        }
    }

    @Override
    public void updateLifecycle(IslandId islandId, IslandLifecycle lifecycle) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(lifecycle, "lifecycle");
        String sql = "UPDATE islands SET lifecycle = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, lifecycle.name());
            stmt.setString(2, islandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to update lifecycle for island: " + islandId, e);
        }
    }

    @Override
    public Optional<IslandFreezeRecord> findFreezeRecord(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        String sql = "SELECT administrative_state, freeze_reason, updated_at FROM islands WHERE id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                AdministrativeState adminState;
                try {
                    adminState = AdministrativeState.valueOf(rs.getString("administrative_state"));
                } catch (Exception e) {
                    adminState = AdministrativeState.NORMAL;
                }
                String reason = rs.getString("freeze_reason");
                Timestamp ts = rs.getTimestamp("updated_at");
                Instant updatedAt = ts != null ? ts.toInstant() : Instant.now();

                if (adminState == AdministrativeState.FROZEN) {
                    return Optional.of(IslandFreezeRecord.frozen(
                            islandId, reason != null ? reason : "Administrative quarantine", null, updatedAt));
                } else {
                    return Optional.of(IslandFreezeRecord.normal(islandId, updatedAt));
                }
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to query freeze record for island: " + islandId, e);
        }
    }
}
