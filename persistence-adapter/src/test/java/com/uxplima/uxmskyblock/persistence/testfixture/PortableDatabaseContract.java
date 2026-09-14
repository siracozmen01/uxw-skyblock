package com.uxplima.uxmskyblock.persistence.testfixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;

/**
 * Reusable dialect-parity contract verifying common, portable persistence operations and
 * {@link MigrationRunner} parity across SQLite, MariaDB, and PostgreSQL.
 */
public final class PortableDatabaseContract {

    private PortableDatabaseContract() {}

    /** Verifies portable SQL table creation, inserts, selects, updates, transactions, and constraints. */
    public static void verifyCommonSqlContract(Database database) throws SQLException {
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            // 1. Table creation with INTEGER, VARCHAR, BIGINT
            stmt.execute("""
                    CREATE TABLE test_dialect_contract (
                        id INT PRIMARY KEY,
                        name VARCHAR(64) NOT NULL,
                        balance_minor BIGINT NOT NULL,
                        active INT NOT NULL
                    )
                    """);

            // 2. Parameterized inserts
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO test_dialect_contract (id, name, balance_minor, active) VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "alpha");
                insert.setLong(3, 10_000_000_000L);
                insert.setInt(4, 1);
                int rows = insert.executeUpdate();
                assertThat(rows).isEqualTo(1);

                insert.setInt(1, 2);
                insert.setString(2, "beta");
                insert.setLong(3, 20_000_000_000L);
                insert.setInt(4, 0);
                insert.executeUpdate();
            }

            // 3. Parameterized select
            try (PreparedStatement select = conn.prepareStatement(
                    "SELECT name, balance_minor, active FROM test_dialect_contract WHERE id = ?")) {
                select.setInt(1, 1);
                try (ResultSet rs = select.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("alpha");
                    assertThat(rs.getLong("balance_minor")).isEqualTo(10_000_000_000L);
                    assertThat(rs.getInt("active")).isEqualTo(1);
                }
            }

            // 4. Update with affected-row count
            try (PreparedStatement update =
                    conn.prepareStatement("UPDATE test_dialect_contract SET balance_minor = ? WHERE id = ?")) {
                update.setLong(1, 15_000_000_000L);
                update.setInt(2, 1);
                int updated = update.executeUpdate();
                assertThat(updated).isEqualTo(1);
            }

            // 5. Unique / Primary Key constraint violation
            assertThatThrownBy(() -> {
                        try (PreparedStatement dup = conn.prepareStatement(
                                "INSERT INTO test_dialect_contract (id, name, balance_minor, active) VALUES (?, ?, ?, ?)")) {
                            dup.setInt(1, 1);
                            dup.setString(2, "duplicate");
                            dup.setLong(3, 0L);
                            dup.setInt(4, 1);
                            dup.executeUpdate();
                        }
                    })
                    .isInstanceOf(SQLException.class);
        }

        // 6. Transaction commit
        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO test_dialect_contract (id, name, balance_minor, active) VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, 3);
                insert.setString(2, "gamma");
                insert.setLong(3, 30_000_000_000L);
                insert.setInt(4, 1);
                insert.executeUpdate();
                conn.commit();
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }

        // Verify row 3 committed
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_dialect_contract WHERE id = 3")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }

        // 7. Transaction rollback
        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO test_dialect_contract (id, name, balance_minor, active) VALUES (?, ?, ?, ?)")) {
                insert.setInt(1, 4);
                insert.setString(2, "delta");
                insert.setLong(3, 40_000_000_000L);
                insert.setInt(4, 1);
                insert.executeUpdate();
                conn.rollback();
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }

        // Verify row 4 was rolled back
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_dialect_contract WHERE id = 4")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(0);
        }
    }

    /** Verifies real uxmlib-storage MigrationRunner parity against the target database. */
    public static void verifyMigrationRunnerParity(Database database) throws SQLException {
        MigrationRunner runner = new MigrationRunner(database);
        assertThat(runner.currentVersion()).isEqualTo(0);

        Migration v1 = new Migration(1, "create test_parity_table", """
                CREATE TABLE test_parity_table (
                    id INT PRIMARY KEY,
                    label VARCHAR(64) NOT NULL
                );
                INSERT INTO test_parity_table (id, label) VALUES (1, 'initial');
                """);

        Migration v2 = new Migration(2, "append to test_parity_table", """
                INSERT INTO test_parity_table (id, label) VALUES (2, 'second');
                """);

        // Apply V1
        int applied1 = runner.apply(List.of(v1));
        assertThat(applied1).isEqualTo(1);
        assertThat(runner.currentVersion()).isEqualTo(1);

        // Apply V2
        int applied2 = runner.apply(List.of(v1, v2));
        assertThat(applied2).isEqualTo(1);
        assertThat(runner.currentVersion()).isEqualTo(2);

        // Idempotent rerun
        int appliedRerun = runner.apply(List.of(v1, v2));
        assertThat(appliedRerun).isEqualTo(0);
        assertThat(runner.currentVersion()).isEqualTo(2);

        // Inspect history table
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + runner.historyTable())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }
}
