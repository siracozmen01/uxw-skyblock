package com.uxplima.uxmskyblock.persistence.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.sql.DialectTransactions;

/**
 * Reading the bank, opening one, and listing what it has done.
 *
 * <p>None of these move money. They sat either side of a two hundred line transaction protocol
 * that does, so reading one meant scrolling past the other.
 *
 * <p>The history is append only and is never rewritten, not by a restore and not by a correction.
 * A mistaken transaction is followed by its reversal, and both stay visible.
 */
final class SqlIslandBankLedger {

    private final Database database;
    private final Dialect dialect;
    private final DialectTransactions tx;

    SqlIslandBankLedger(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.dialect = database.dialect();
        this.tx = new DialectTransactions(this.dialect);
    }

    public Optional<IslandBank> findBankByIslandId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        String sql = """
                SELECT island_id, primary_balance_minor_units, crystals_balance, exp_balance, version, updated_at
                FROM island_banks
                WHERE island_id = ?
                """;
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapIslandBank(rs, islandId));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to query island bank for island " + islandId, e);
        }
    }

    public IslandBank createBank(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        try (Connection connection = database.connection()) {
            tx.begin(connection);
            try {
                String selectSql = """
                        SELECT island_id, primary_balance_minor_units, crystals_balance, exp_balance, version, updated_at
                        FROM island_banks
                        WHERE island_id = ?
                        """;
                try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                    ps.setString(1, islandId.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            IslandBank bank = mapIslandBank(rs, islandId);
                            tx.commit(connection);
                            return bank;
                        }
                    }
                }

                String insertSql = """
                        INSERT INTO island_banks (
                            island_id, primary_balance_minor_units, crystals_balance, exp_balance, version, updated_at
                        ) VALUES (?, 0, 0, 0, 1, CURRENT_TIMESTAMP)
                        """;
                try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
                    ps.setString(1, islandId.value().toString());
                    ps.executeUpdate();
                }

                // Query back with exact DB timestamp
                IslandBank bank;
                try (PreparedStatement ps = connection.prepareStatement(selectSql)) {
                    ps.setString(1, islandId.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IslandBankPersistenceException("Failed to retrieve newly created island bank");
                        }
                        bank = mapIslandBank(rs, islandId);
                    }
                }

                tx.commit(connection);
                return bank;
            } catch (SQLException e) {
                tx.rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to create island bank for island " + islandId, e);
        }
    }

    public List<BankTransaction> getTransactionHistory(IslandId islandId, int limit) {
        Objects.requireNonNull(islandId, "islandId");
        if (limit <= 0) {
            return List.of();
        }

        String sql = """
                SELECT transaction_id, operation_id, island_id, actor_uuid,
                       currency_id, currency_scale, delta_amount_minor_units,
                       resulting_balance_minor_units, reason, created_at
                FROM bank_transactions
                WHERE island_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<BankTransaction> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(new BankTransaction(
                            UUID.fromString(rs.getString("transaction_id")),
                            UUID.fromString(rs.getString("operation_id")),
                            islandId,
                            UUID.fromString(rs.getString("actor_uuid")),
                            rs.getString("currency_id"),
                            rs.getInt("currency_scale"),
                            rs.getLong("delta_amount_minor_units"),
                            rs.getLong("resulting_balance_minor_units"),
                            rs.getString("reason"),
                            rs.getTimestamp("created_at").toInstant()));
                }
                return Collections.unmodifiableList(list);
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to query transaction history for island " + islandId, e);
        }
    }

    private static IslandBank mapIslandBank(ResultSet rs, IslandId islandId) throws SQLException {
        return new IslandBank(
                islandId,
                rs.getLong("primary_balance_minor_units"),
                rs.getLong("crystals_balance"),
                rs.getLong("exp_balance"),
                rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
