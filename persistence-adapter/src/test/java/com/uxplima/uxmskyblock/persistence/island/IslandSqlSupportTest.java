package com.uxplima.uxmskyblock.persistence.island;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transaction lifecycle every island write goes through.
 *
 * <p>SQLite is the odd one out: it takes its write lock with {@code BEGIN IMMEDIATE} rather than by
 * turning auto commit off, so every one of these four methods has a branch nothing was exercising.
 * A rollback that does not roll back is a dupe factory, and the only thing standing between this
 * code and that is a test that opens a real connection and looks at the table afterwards.
 */
class IslandSqlSupportTest {

    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ledger (id INTEGER PRIMARY KEY, note TEXT NOT NULL)");
        }
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("The three dialects this plugin ships on are accepted")
    void theShippedDialectsAreAccepted() {
        for (Dialect dialect : new Dialect[] {Dialect.SQLITE, Dialect.MYSQL, Dialect.POSTGRES}) {
            assertThatCode(() -> IslandSqlSupport.validateDialect(dialect))
                    .describedAs("%s is one of the three", dialect)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("A dialect nobody tested against is refused by name rather than failing later")
    void anUntestedDialectIsRefused() {
        for (Dialect dialect : new Dialect[] {Dialect.H2, Dialect.GENERIC}) {
            assertThatThrownBy(() -> IslandSqlSupport.validateDialect(dialect))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(dialect.toString());
        }
    }

    @Test
    @DisplayName("Each dialect gets the interval expression its own parser understands")
    void eachDialectGetsItsOwnIntervalExpression() {
        assertThat(IslandSqlSupport.dbNowPlus(Dialect.SQLITE, 30)).isEqualTo("DATETIME('now', '+30 seconds')");
        assertThat(IslandSqlSupport.dbNowPlus(Dialect.MYSQL, 30)).isEqualTo("CURRENT_TIMESTAMP + INTERVAL 30 SECOND");
        assertThat(IslandSqlSupport.dbNowPlus(Dialect.POSTGRES, 30))
                .isEqualTo("CURRENT_TIMESTAMP + INTERVAL '30 seconds'");
    }

    @Test
    @DisplayName("The SQLite interval expression is one SQLite really evaluates, not one that only reads right")
    void theSqliteIntervalIsReallyInTheFuture() throws Exception {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT " + IslandSqlSupport.dbNowPlus(Dialect.SQLITE, 60) + " > DATETIME('now')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1))
                    .describedAs("now plus sixty seconds is after now")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("A committed transaction keeps its row")
    void aCommittedTransactionKeepsItsRow() throws Exception {
        try (Connection connection = database.connection()) {
            IslandSqlSupport.beginTransaction(connection, Dialect.SQLITE);
            insert(connection, 1, "kept");
            IslandSqlSupport.commitTransaction(connection, Dialect.SQLITE);
        }

        assertThat(noteOf(1)).isEqualTo("kept");
    }

    @Test
    @DisplayName("A rolled back transaction really loses its row, which is the whole point")
    void aRolledBackTransactionLosesItsRow() throws Exception {
        try (Connection connection = database.connection()) {
            IslandSqlSupport.beginTransaction(connection, Dialect.SQLITE);
            insert(connection, 2, "discarded");
            IslandSqlSupport.rollbackTransaction(connection, Dialect.SQLITE);
        }

        assertThat(noteOf(2)).isNull();
    }

    @Test
    @DisplayName("A rollback with nothing to roll back is quiet rather than a second failure")
    void aRollbackWithNothingOpenIsQuiet() throws Exception {
        try (Connection connection = database.connection()) {
            assertThatCode(() -> IslandSqlSupport.rollbackTransaction(connection, Dialect.SQLITE))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("SQLite is left alone by the auto commit reset, because it never turned it off")
    void sqliteIsLeftAloneByTheAutoCommitReset() throws Exception {
        try (Connection connection = database.connection()) {
            boolean before = connection.getAutoCommit();
            IslandSqlSupport.resetAutoCommitQuietly(connection, Dialect.SQLITE, false);
            assertThat(connection.getAutoCommit())
                    .describedAs("the SQLite branch touches nothing")
                    .isEqualTo(before);
        }
    }

    @Test
    @DisplayName("A closed connection does not make the cleanup throw on top of whatever closed it")
    void cleanupOnAClosedConnectionIsQuiet() throws Exception {
        Connection connection = database.connection();
        connection.close();

        assertThatCode(() -> {
                    IslandSqlSupport.rollbackTransaction(connection, Dialect.MYSQL);
                    IslandSqlSupport.resetAutoCommitQuietly(connection, Dialect.MYSQL, true);
                })
                .describedAs("cleanup runs in a finally block, where a second throw hides the first")
                .doesNotThrowAnyException();
    }

    private void insert(Connection connection, int id, String note) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ledger (id, note) VALUES (" + id + ", '" + note + "')");
        }
    }

    private @org.jspecify.annotations.Nullable String noteOf(int id) throws Exception {
        try (Connection connection = database.connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT note FROM ledger WHERE id = " + id)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
