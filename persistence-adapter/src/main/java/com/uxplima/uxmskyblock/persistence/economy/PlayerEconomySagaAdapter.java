package com.uxplima.uxmskyblock.persistence.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaPort;
import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
import com.uxplima.uxmskyblock.core.domain.economy.SagaType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Production SQL implementation of {@link EconomySagaPort} providing write-ahead saga
 * persistence and recovery queries.
 */
public final class PlayerEconomySagaAdapter implements EconomySagaPort {

    private final Database database;

    public PlayerEconomySagaAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void createSaga(EconomySagaRecord saga) {
        Objects.requireNonNull(saga, "saga");
        String sql = """
                INSERT INTO economy_sagas (
                    saga_id, player_uuid, profile_id, island_id, saga_type, state,
                    amount_minor_units, currency, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, saga.sagaId().value());
            ps.setString(2, saga.playerUuid().value().toString());
            ps.setString(3, saga.profileId().value().toString());
            ps.setString(4, saga.islandId().value().toString());
            ps.setString(5, saga.sagaType().name());
            ps.setString(6, saga.state().name());
            ps.setLong(7, saga.amountMinorUnits());
            ps.setString(8, saga.currency());
            ps.setTimestamp(9, Timestamp.from(saga.expiresAt()));
            ps.setTimestamp(10, Timestamp.from(saga.createdAt()));
            ps.setTimestamp(11, Timestamp.from(saga.updatedAt()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to persist economy saga: " + saga.sagaId().value(), e);
        }
    }

    @Override
    public void updateState(SagaId sagaId, SagaState newState, Instant updatedAt) {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(updatedAt, "updatedAt");

        String sql = "UPDATE economy_sagas SET state = ?, updated_at = ? WHERE saga_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newState.name());
            ps.setTimestamp(2, Timestamp.from(updatedAt));
            ps.setString(3, sagaId.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update economy saga state: " + sagaId.value(), e);
        }
    }

    @Override
    public Optional<EconomySagaRecord> findSagaById(SagaId sagaId) {
        Objects.requireNonNull(sagaId, "sagaId");
        String sql = """
                SELECT saga_id, player_uuid, profile_id, island_id, saga_type, state,
                       amount_minor_units, currency, expires_at, created_at, updated_at
                FROM economy_sagas WHERE saga_id = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sagaId.value());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find economy saga by id: " + sagaId.value(), e);
        }
    }

    @Override
    public List<EconomySagaRecord> findIncompleteSagas(Instant expiredBefore) {
        Objects.requireNonNull(expiredBefore, "expiredBefore");
        String sql = """
                SELECT saga_id, player_uuid, profile_id, island_id, saga_type, state,
                       amount_minor_units, currency, expires_at, created_at, updated_at
                FROM economy_sagas
                WHERE state IN ('STARTED', 'COMPENSATING') AND expires_at <= ?
                ORDER BY expires_at ASC
                """;
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(expiredBefore));
            try (ResultSet rs = ps.executeQuery()) {
                List<EconomySagaRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return List.copyOf(list);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query incomplete economy sagas", e);
        }
    }

    @Override
    public int purgeSettledBefore(Instant before) {
        Objects.requireNonNull(before, "before must not be null");

        // A failed saga is kept: a money movement that went wrong is the evidence an operator needs.
        String sql = "DELETE FROM economy_sagas WHERE state IN (?, ?) AND updated_at < ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, SagaState.COMMITTED.name());
            stmt.setString(2, SagaState.ROLLED_BACK.name());
            stmt.setTimestamp(3, Timestamp.from(before));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to purge settled economy sagas before " + before, e);
        }
    }

    private static EconomySagaRecord mapRow(ResultSet rs) throws SQLException {
        SagaId sagaId = SagaId.of(rs.getString("saga_id"));
        PlayerUuid playerUuid = new PlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        ProfileId profileId = new ProfileId(UUID.fromString(rs.getString("profile_id")));
        IslandId islandId = new IslandId(UUID.fromString(rs.getString("island_id")));
        SagaType sagaType = SagaType.valueOf(rs.getString("saga_type"));
        SagaState state = SagaState.valueOf(rs.getString("state"));
        long amountMinorUnits = rs.getLong("amount_minor_units");
        String currency = rs.getString("currency");
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        return new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                sagaType,
                state,
                amountMinorUnits,
                currency,
                expiresAt,
                createdAt,
                updatedAt);
    }
}
