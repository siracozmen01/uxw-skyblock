package com.uxplima.uxmskyblock.persistence.bank;

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
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.sql.UniqueViolations;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL implementation of {@link IslandBankruptcyStoragePort} persisting
 * island bankruptcy and arrears records across SQLite, MySQL/MariaDB, and PostgreSQL.
 */
public final class SqlIslandBankruptcyStorageAdapter implements IslandBankruptcyStoragePort {

    private final Database database;

    public SqlIslandBankruptcyStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public Optional<IslandBankruptcyRecord> findByIslandId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT island_id, status, debt_minor_units, grace_until, updated_at, upkeep_period
                FROM island_bankruptcies
                WHERE island_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to query bankruptcy record for island " + islandId, e);
        }
    }

    @Override
    public void save(IslandBankruptcyRecord record) {
        Objects.requireNonNull(record, "record must not be null");

        String updateSql = """
                UPDATE island_bankruptcies
                SET status = ?, debt_minor_units = ?, grace_until = ?, updated_at = ?, upkeep_period = ?
                WHERE island_id = ?
                """;
        String insertSql = """
                INSERT INTO island_bankruptcies (
                    island_id, status, debt_minor_units, grace_until, updated_at, upkeep_period
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        Timestamp graceTs = record.graceUntil() != null ? Timestamp.from(record.graceUntil()) : null;
        Timestamp updatedTs = Timestamp.from(record.updatedAt());

        // The row is updated first and inserted only when there was none, so a save is one statement
        // and not a question followed by one.
        try (Connection conn = database.connection()) {
            if (update(conn, updateSql, record, graceTs, updatedTs) > 0) {
                return;
            }
            try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                insert.setString(1, record.islandId().value().toString());
                insert.setString(2, record.status().name());
                insert.setLong(3, record.debtMinorUnits());
                insert.setTimestamp(4, graceTs);
                insert.setTimestamp(5, updatedTs);
                insert.setLong(6, record.upkeepPeriod());
                insert.executeUpdate();
            } catch (SQLException collided) {
                if (!UniqueViolations.isUniqueViolation(collided)) {
                    throw collided;
                }
                update(conn, updateSql, record, graceTs, updatedTs);
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException(
                    "Failed to save bankruptcy record for island " + record.islandId(), e);
        }
    }

    @Override
    public List<IslandBankruptcyRecord> findAllBankruptcies() {
        String sql = """
                SELECT island_id, status, debt_minor_units, grace_until, updated_at, upkeep_period
                FROM island_bankruptcies
                WHERE status IN ('GRACE', 'LOCKED')
                ORDER BY updated_at ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            List<IslandBankruptcyRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(mapRow(rs));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to query all active bankruptcies", e);
        }
    }

    @Override
    public void deleteByIslandId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = "DELETE FROM island_bankruptcies WHERE island_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to delete bankruptcy record for island " + islandId, e);
        }
    }

    private static int update(
            Connection conn,
            String updateSql,
            IslandBankruptcyRecord record,
            @Nullable Timestamp graceTs,
            Timestamp updatedTs)
            throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(updateSql)) {
            update.setString(1, record.status().name());
            update.setLong(2, record.debtMinorUnits());
            update.setTimestamp(3, graceTs);
            update.setTimestamp(4, updatedTs);
            update.setLong(5, record.upkeepPeriod());
            update.setString(6, record.islandId().value().toString());
            return update.executeUpdate();
        }
    }

    private static IslandBankruptcyRecord mapRow(ResultSet rs) throws SQLException {
        IslandId islandId = IslandId.of(UUID.fromString(rs.getString("island_id")));
        BankruptcyStatus status = BankruptcyStatus.valueOf(rs.getString("status"));
        long debtMinorUnits = rs.getLong("debt_minor_units");
        Timestamp graceTs = rs.getTimestamp("grace_until");
        Instant graceUntil = graceTs != null ? graceTs.toInstant() : null;
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : Instant.now();

        return new IslandBankruptcyRecord(
                islandId, status, debtMinorUnits, graceUntil, updatedAt, rs.getLong("upkeep_period"));
    }
}
