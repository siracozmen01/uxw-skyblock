package com.uxplima.uxmskyblock.persistence.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.PortableDatabaseContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * P1-007 Fast Local Lane test suite exercising SQLite.
 *
 * <p>Proves portable common SQL contract, {@link com.uxplima.uxmlib.storage.migration.MigrationRunner}
 * parity, and SQLite's distinct single-writer serialization model via {@code BEGIN IMMEDIATE}.
 * Explicitly verifies that SQLite does NOT support row-level locks ({@code SELECT ... FOR UPDATE}).
 */
class SqliteFastLaneTest {

    @TempDir
    Path tempDir;

    private Database database;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("1. SQLite Portable Common SQL Contract: verifies DDL, CRUD, transactions, and constraints")
    void verifiesPortableCommonSqlContract() throws Exception {
        PortableDatabaseContract.verifyCommonSqlContract(database);
    }

    @Test
    @DisplayName("2. SQLite MigrationRunner Parity: verifies history tracking, version ordering, and idempotency")
    void verifiesMigrationRunnerParity() throws Exception {
        PortableDatabaseContract.verifyMigrationRunnerParity(database);
    }

    @Test
    @DisplayName("3. SQLite Row-Lock Invariant: verifies SQLite rejects SELECT ... FOR UPDATE with syntax error")
    void verifiesSqliteRejectsSelectForUpdate() throws Exception {
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE test_lock (id INT PRIMARY KEY, val TEXT)");
            stmt.execute("INSERT INTO test_lock (id, val) VALUES (1, 'locked')");

            assertThatThrownBy(() -> stmt.executeQuery("SELECT val FROM test_lock WHERE id = 1 FOR UPDATE"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("syntax error");
        }
    }

    @Test
    @DisplayName(
            "4. SQLite Writer Serialization: verifies single-writer exclusivity via BEGIN IMMEDIATE between distinct connections")
    void verifiesSqliteWriterSerializationViaBeginImmediate() throws Exception {
        Path dbFile = tempDir.resolve("sqlite-writer-serialization.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        // Initialize schema using first connection
        try (Connection initConn = DriverManager.getConnection(jdbcUrl);
                Statement stmt = initConn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("CREATE TABLE test_writer_lock (id INT PRIMARY KEY, counter INT NOT NULL)");
            stmt.execute("INSERT INTO test_writer_lock (id, counter) VALUES (1, 100)");
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aAcquiredWriterLock = new CountDownLatch(1);
        CountDownLatch bAttemptedWrite = new CountDownLatch(1);
        CountDownLatch aProceedToCommit = new CountDownLatch(1);
        AtomicBoolean bBlockedBeforeACommit = new AtomicBoolean(false);
        boolean bCompletedAfterACommit = false;

        try (Connection connA = DriverManager.getConnection(jdbcUrl);
                Connection connB = DriverManager.getConnection(jdbcUrl)) {
            // Busy timeout of 200ms on connB
            try (Statement sB = connB.createStatement()) {
                sB.execute("PRAGMA busy_timeout = 200;");
            }

            // Connection A acquires exclusive writer slot via BEGIN IMMEDIATE
            Future<?> futureA = executor.submit(() -> {
                try (Statement stmtA = connA.createStatement()) {
                    stmtA.execute("BEGIN IMMEDIATE");
                    stmtA.executeUpdate("UPDATE test_writer_lock SET counter = 101 WHERE id = 1");
                    aAcquiredWriterLock.countDown();

                    // Hold writer lock until instructed to commit
                    boolean proceed = aProceedToCommit.await(5, TimeUnit.SECONDS);
                    assertThat(proceed).isTrue();
                    stmtA.execute("COMMIT");
                } catch (Exception e) {
                    throw new RuntimeException("Connection A writer execution failed", e);
                }
            });

            // Connection B attempts to acquire writer lock while A holds it
            Future<?> futureB = executor.submit(() -> {
                try {
                    boolean aLocked = aAcquiredWriterLock.await(5, TimeUnit.SECONDS);
                    assertThat(aLocked).isTrue();

                    bAttemptedWrite.countDown();

                    // Connection B attempts write transaction; must fail or block due to A holding writer lock
                    try (Statement stmtB = connB.createStatement()) {
                        stmtB.execute("BEGIN IMMEDIATE");
                        stmtB.executeUpdate("UPDATE test_writer_lock SET counter = 102 WHERE id = 1");
                        stmtB.execute("COMMIT");
                    } catch (SQLException busy) {
                        // Expected SQLITE_BUSY while A holds writer lock
                        bBlockedBeforeACommit.set(true);
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Connection B writer execution failed", e);
                }
            });

            // Wait until B reached attempt point
            assertThat(bAttemptedWrite.await(5, TimeUnit.SECONDS)).isTrue();
            // Let B fail or wait with busy timeout
            futureB.get(2, TimeUnit.SECONDS);
            assertThat(bBlockedBeforeACommit.get())
                    .as("Connection B must be serialized/blocked by SQLite while Connection A holds BEGIN IMMEDIATE")
                    .isTrue();

            // Now signal A to commit
            aProceedToCommit.countDown();
            futureA.get(5, TimeUnit.SECONDS);

            // Connection B now retries after A committed and succeeds
            try (Statement stmtB = connB.createStatement()) {
                stmtB.execute("BEGIN IMMEDIATE");
                stmtB.executeUpdate("UPDATE test_writer_lock SET counter = 200 WHERE id = 1");
                stmtB.execute("COMMIT");
                bCompletedAfterACommit = true;
            }

            assertThat(bCompletedAfterACommit).isTrue();

            // Verify final deterministic state
            try (Statement checkStmt = connA.createStatement();
                    ResultSet rs = checkStmt.executeQuery("SELECT counter FROM test_writer_lock WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("counter")).isEqualTo(200);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
