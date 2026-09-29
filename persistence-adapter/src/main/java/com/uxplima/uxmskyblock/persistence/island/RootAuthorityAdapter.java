package com.uxplima.uxmskyblock.persistence.island;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The lease of a root that is not an island, in {@code game_mode_instance_authorities} or
 * {@code mode_owned_authorities}, taken, renewed and taken over by the statements that hold an island's.
 *
 * <p>It lives beside the island's adapter because it is the island's algorithm, run against another
 * table: the same transaction on each engine, the same epoch fencing and the same reading of a lease
 * against the database's own clock.
 */
public final class RootAuthorityAdapter implements RootAuthorityPort {

    private final Database database;
    private final Dialect dialect;

    public RootAuthorityAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        IslandSqlSupport.validateDialect(this.dialect);
    }

    @Override
    public IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(node, "node");
        Table table = Table.of(root);
        String sql = "INSERT INTO " + table.name + " (" + table.keyColumns
                + ", authoritative_node, authority_epoch, lease_expires_at, updated_at) VALUES (" + table.keyMarks
                + ", ?, 1, " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds) + ", CURRENT_TIMESTAMP)";
        return write(
                root,
                "acquire",
                sql,
                statement -> {
                    int next = bindKey(statement, 1, root);
                    statement.setString(next, node.value());
                },
                1L);
    }

    @Override
    public IslandAuthorityOutcome renew(AuthorityRoot root, ServerNodeId node, long expectedEpoch, int leaseSeconds) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(node, "node");
        Table table = Table.of(root);
        String sql = "UPDATE " + table.name + " SET lease_expires_at = "
                + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds) + ", updated_at = CURRENT_TIMESTAMP"
                + " WHERE " + table.keyWhere + " AND authoritative_node = ? AND authority_epoch = ?"
                + " AND lease_expires_at >= CURRENT_TIMESTAMP";
        return write(
                root,
                "renew",
                sql,
                statement -> {
                    int next = bindKey(statement, 1, root);
                    statement.setString(next, node.value());
                    statement.setLong(next + 1, expectedEpoch);
                },
                expectedEpoch);
    }

    @Override
    public IslandAuthorityOutcome takeover(
            AuthorityRoot root, ServerNodeId newNode, long expectedEpoch, int leaseSeconds) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(newNode, "newNode");
        Table table = Table.of(root);
        String sql = "UPDATE " + table.name + " SET authoritative_node = ?, authority_epoch = authority_epoch + 1,"
                + " lease_expires_at = " + IslandSqlSupport.dbNowPlus(dialect, leaseSeconds)
                + ", updated_at = CURRENT_TIMESTAMP"
                + " WHERE " + table.keyWhere + " AND authority_epoch = ? AND lease_expires_at < CURRENT_TIMESTAMP";
        return write(
                root,
                "take over",
                sql,
                statement -> {
                    statement.setString(1, newNode.value());
                    int next = bindKey(statement, 2, root);
                    statement.setLong(next, expectedEpoch);
                },
                expectedEpoch + 1);
    }

    @Override
    public Optional<RootAuthorityRecord> find(AuthorityRoot root) {
        Objects.requireNonNull(root, "root");
        Table table = Table.of(root);
        // Read against the database's own now, as the island's lease is: two clocks in two zones
        // would otherwise read a live lease as long past.
        String sql = "SELECT authoritative_node, authority_epoch, lease_expires_at, CURRENT_TIMESTAMP AS database_now"
                + " FROM " + table.name + " WHERE " + table.keyWhere;
        try (Connection conn = database.connection();
                PreparedStatement statement = conn.prepareStatement(sql)) {
            bindKey(statement, 1, root);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Instant here = Instant.now();
                Instant databaseNow = rs.getTimestamp("database_now").toInstant();
                Instant lease = here.plus(Duration.between(
                        databaseNow, rs.getTimestamp("lease_expires_at").toInstant()));
                return Optional.of(new RootAuthorityRecord(
                        root,
                        new ServerNodeId(rs.getString("authoritative_node")),
                        rs.getLong("authority_epoch"),
                        lease));
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to read the lease of " + root, e);
        }
    }

    private IslandAuthorityOutcome write(AuthorityRoot root, String what, String sql, Binder binder, long epoch) {
        try (Connection conn = database.connection()) {
            boolean previous = conn.getAutoCommit();
            IslandSqlSupport.beginTransaction(conn, dialect);
            try {
                int affected;
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    binder.bind(statement);
                    affected = statement.executeUpdate();
                }
                IslandSqlSupport.commitTransaction(conn, dialect);
                return affected == 1 ? IslandAuthorityOutcome.success(epoch) : IslandAuthorityOutcome.rejected();
            } catch (SQLException e) {
                // A second acquire meets the primary key: somebody holds the root already.
                IslandSqlSupport.rollbackTransaction(conn, dialect);
                return IslandAuthorityOutcome.rejected();
            } finally {
                IslandSqlSupport.resetAutoCommitQuietly(conn, dialect, previous);
            }
        } catch (SQLException e) {
            throw new IslandPersistenceException("Failed to " + what + " the lease of " + root, e);
        }
    }

    /** Binds the root's key from {@code first} and returns the next free parameter. */
    private static int bindKey(PreparedStatement statement, int first, AuthorityRoot root) throws SQLException {
        if (root.scope() == AuthorityRoot.Scope.GAME_MODE_INSTANCE) {
            statement.setString(first, root.key());
            return first + 1;
        }
        statement.setString(first, root.providerId());
        statement.setString(first + 1, root.key());
        return first + 2;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private enum Table {
        INSTANCE("game_mode_instance_authorities", "instance_id", "?", "instance_id = ?"),
        MODE_OWNED("mode_owned_authorities", "provider_id, root_key", "?, ?", "provider_id = ? AND root_key = ?");

        private final String name;
        private final String keyColumns;
        private final String keyMarks;
        private final String keyWhere;

        Table(String name, String keyColumns, String keyMarks, String keyWhere) {
            this.name = name;
            this.keyColumns = keyColumns;
            this.keyMarks = keyMarks;
            this.keyWhere = keyWhere;
        }

        static Table of(AuthorityRoot root) {
            return root.scope() == AuthorityRoot.Scope.GAME_MODE_INSTANCE ? INSTANCE : MODE_OWNED;
        }
    }
}
