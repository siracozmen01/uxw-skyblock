package com.uxplima.uxmskyblock.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a transaction is opened, and why it matters which database is underneath.
 *
 * <p>Four adapters each carried their own copy of these three methods before they moved here, and
 * nothing tested any copy. The claim this class exists to make is the one worth pinning: SQLite
 * takes its write lock when a transaction says BEGIN IMMEDIATE rather than at the first write, so
 * two writers racing get a clean refusal instead of one of them losing an update later.
 */
class DialectTransactionsTest {

    private @org.jspecify.annotations.Nullable Database database;

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    private void seed(Path dir) throws SQLException {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("transactions.db"));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE counters (name VARCHAR(16) PRIMARY KEY, value INT NOT NULL)");
            stmt.execute("INSERT INTO counters (name, value) VALUES ('a', 1)");
        }
    }

    private int valueOf(String name) throws SQLException {
        try (Connection conn = java.util.Objects.requireNonNull(database).connection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT value FROM counters WHERE name = '" + name + "'")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    @Test
    @DisplayName("On SQLite the write lock is taken when the transaction opens, not at the first write")
    void thelockIsTakenUpFront(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("transactions.db");
        seed(dir);
        DialectTransactions transactions = new DialectTransactions(Dialect.SQLITE);

        try (Connection first = java.util.Objects.requireNonNull(database).connection()) {
            transactions.begin(first);
            try {
                // Nothing has been written yet. A lock taken at the first write would let the
                // second writer in here, and one of the two updates would be lost later.
                // A second connection straight to the file, not through the pool. The pool hands
                // out one connection for SQLite, so the only way another writer reaches this file
                // is the way a second process would: on its own.
                try (Connection second = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                        Statement stmt = second.createStatement()) {
                    stmt.execute("PRAGMA busy_timeout = 250");
                    assertThatThrownBy(() -> stmt.execute("UPDATE counters SET value = 99 WHERE name = 'a'"))
                            .describedAs("a clean refusal is the point of BEGIN IMMEDIATE")
                            .isInstanceOf(SQLException.class);
                }

                try (Statement stmt = first.createStatement()) {
                    stmt.execute("UPDATE counters SET value = 2 WHERE name = 'a'");
                }
                transactions.commit(first);
            } catch (RuntimeException | SQLException e) {
                transactions.rollbackQuietly(first);
                throw e;
            }
        }

        assertThat(valueOf("a")).describedAs("the first writer's update stands").isEqualTo(2);
    }

    @Test
    @DisplayName("On SQLite a rollback undoes everything the transaction wrote")
    void arollbackUndoesTheWrites(@TempDir Path dir) throws Exception {
        seed(dir);
        DialectTransactions transactions = new DialectTransactions(Dialect.SQLITE);

        try (Connection conn = java.util.Objects.requireNonNull(database).connection()) {
            transactions.begin(conn);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE counters SET value = 77 WHERE name = 'a'");
                stmt.execute("INSERT INTO counters (name, value) VALUES ('b', 5)");
            }
            transactions.rollbackQuietly(conn);
        }

        assertThat(valueOf("a")).describedAs("put back the way it was").isEqualTo(1);
        assertThat(valueOf("b")).describedAs("never existed").isEqualTo(-1);
    }

    @Test
    @DisplayName("On SQLite autocommit is left exactly as it was found")
    void autocommitIsLeftAlone(@TempDir Path dir) throws Exception {
        seed(dir);
        DialectTransactions transactions = new DialectTransactions(Dialect.SQLITE);

        try (Connection conn = java.util.Objects.requireNonNull(database).connection()) {
            boolean before = conn.getAutoCommit();
            transactions.begin(conn);
            transactions.commit(conn);
            transactions.resetAutoCommitQuietly(conn, before);

            assertThat(conn.getAutoCommit())
                    .describedAs("BEGIN IMMEDIATE never touched it, so nothing has to put it back")
                    .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("On a server database the transaction is autocommit off, commit, and rollback")
    void aserverDatabaseUsesAutocommit() throws Exception {
        for (Dialect dialect : new Dialect[] {Dialect.MYSQL, Dialect.POSTGRES}) {
            DialectTransactions transactions = new DialectTransactions(dialect);
            Connection connection = mock(Connection.class);

            transactions.begin(connection);
            verify(connection).setAutoCommit(false);
            verify(connection, never()).createStatement();

            transactions.commit(connection);
            verify(connection).commit();

            transactions.rollbackQuietly(connection);
            verify(connection).rollback();

            transactions.resetAutoCommitQuietly(connection, true);
            verify(connection).setAutoCommit(true);
        }
    }

    @Test
    @DisplayName("A rollback that fails stays quiet, because something has already gone wrong")
    void afailingRollbackStaysQuiet() throws Exception {
        DialectTransactions transactions = new DialectTransactions(Dialect.POSTGRES);
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("the connection is gone")).when(connection).rollback();

        assertThatCode(() -> transactions.rollbackQuietly(connection))
                .describedAs("throwing here would replace the original failure with this one")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A connection that cannot be put back stays quiet too")
    void afailingResetStaysQuiet() throws Exception {
        DialectTransactions transactions = new DialectTransactions(Dialect.MYSQL);
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("the connection is gone")).when(connection).setAutoCommit(true);

        assertThatCode(() -> transactions.resetAutoCommitQuietly(connection, true))
                .doesNotThrowAnyException();
    }
}
