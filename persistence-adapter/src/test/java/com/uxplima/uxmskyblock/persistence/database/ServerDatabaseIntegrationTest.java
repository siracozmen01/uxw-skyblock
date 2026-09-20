package com.uxplima.uxmskyblock.persistence.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.PortableDatabaseContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * P1-007 Real Server-SQL Integration Lane test suite exercising containerized MariaDB and PostgreSQL.
 *
 * <p>Tagged {@code @Tag("database-integration")} so it runs exclusively under the dedicated
 * {@code :persistence-adapter:databaseIntegrationTest} task, keeping the default developer lane Docker-free.
 *
 * <p>Proves portable common SQL contract, {@link com.uxplima.uxmlib.storage.migration.MigrationRunner}
 * parity, and canonical row-level locking serialization via {@code SELECT ... FOR UPDATE} across
 * distinct concurrent transactions.
 */
@Tag("database-integration")
@SuppressWarnings("NullAway")
class ServerDatabaseIntegrationTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    private static Database mariaDatabase;
    private static Database postgresDatabase;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (mariaDbContainer != null) {
            mariaDatabase = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        }

        postgresContainer = DatabaseTestFixture.startPostgresIfEnabled();
        if (postgresContainer != null) {
            postgresDatabase = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDatabase != null && !mariaDatabase.isClosed()) {
            mariaDatabase.close();
        }
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }

        if (postgresDatabase != null && !postgresDatabase.isClosed()) {
            postgresDatabase.close();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    // ==========================================
    // MariaDB Tests
    // ==========================================

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB 1: Portable Common SQL Contract")
    void verifiesMariaDbCommonSqlContract() throws Exception {
        PortableDatabaseContract.verifyCommonSqlContract(mariaDatabase);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB 2: MigrationRunner Parity")
    void verifiesMariaDbMigrationRunnerParity() throws Exception {
        PortableDatabaseContract.verifyMigrationRunnerParity(mariaDatabase);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb
    @DisplayName("MariaDB 3: Canonical Row Locking via SELECT ... FOR UPDATE")
    void verifiesMariaDbRowLockSerialization() throws Exception {
        verifyServerRowLockSerialization(mariaDatabase, "mariadb_lock");
    }

    // ==========================================
    // PostgreSQL Tests
    // ==========================================

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL 1: Portable Common SQL Contract")
    void verifiesPostgresCommonSqlContract() throws Exception {
        PortableDatabaseContract.verifyCommonSqlContract(postgresDatabase);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL 2: MigrationRunner Parity")
    void verifiesPostgresMigrationRunnerParity() throws Exception {
        PortableDatabaseContract.verifyMigrationRunnerParity(postgresDatabase);
    }

    @Test
    @com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres
    @DisplayName("PostgreSQL 3: Canonical Row Locking via SELECT ... FOR UPDATE")
    void verifiesPostgresRowLockSerialization() throws Exception {
        verifyServerRowLockSerialization(postgresDatabase, "postgres_lock");
    }

    // ==========================================
    // Shared Server Row-Lock Serialization Proof
    // ==========================================

    private static void verifyServerRowLockSerialization(Database database, String tableName) throws Exception {
        // Initialize fixture table
        try (Connection initConn = database.connection();
                Statement stmt = initConn.createStatement()) {
            stmt.execute("CREATE TABLE " + tableName
                    + " (id VARCHAR(64) PRIMARY KEY, locked_by VARCHAR(64) NOT NULL, counter INT NOT NULL)");
            stmt.execute("INSERT INTO " + tableName + " (id, locked_by, counter) VALUES ('key_1', 'initial', 100)");
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch aLockAcquired = new CountDownLatch(1);
        CountDownLatch bLockAttemptStarted = new CountDownLatch(1);
        CountDownLatch aReleaseSignal = new CountDownLatch(1);
        CountDownLatch bLockAcquired = new CountDownLatch(1);

        try (Connection connA = database.connection();
                Connection connB = database.connection()) {
            connA.setAutoCommit(false);
            connB.setAutoCommit(false);

            // Transaction A: begin, SELECT ... FOR UPDATE, signal A_LOCK_ACQUIRED, wait for release signal
            Future<?> futureA = executor.submit(() -> {
                try {
                    try (PreparedStatement lockStmtA =
                            connA.prepareStatement("SELECT counter FROM " + tableName + " WHERE id = ? FOR UPDATE")) {
                        lockStmtA.setString(1, "key_1");
                        try (ResultSet rsA = lockStmtA.executeQuery()) {
                            assertThat(rsA.next()).isTrue();
                            assertThat(rsA.getInt("counter")).isEqualTo(100);
                        }
                    }
                    aLockAcquired.countDown();

                    // Wait for release signal
                    boolean proceed = aReleaseSignal.await(10, TimeUnit.SECONDS);
                    assertThat(proceed).isTrue();

                    try (PreparedStatement updateStmtA = connA.prepareStatement(
                            "UPDATE " + tableName + " SET counter = 101, locked_by = 'node_a' WHERE id = ?")) {
                        updateStmtA.setString(1, "key_1");
                        updateStmtA.executeUpdate();
                    }
                    connA.commit();
                } catch (Exception e) {
                    throw new RuntimeException("Transaction A failed holding row lock", e);
                }
            });

            // Transaction B: wait for A_LOCK_ACQUIRED, signal B_LOCK_ATTEMPT_STARTED immediately before JDBC lock call,
            // execute conflicting SELECT ... FOR UPDATE, signal B_LOCK_ACQUIRED only after JDBC call returns
            Future<?> futureB = executor.submit(() -> {
                try {
                    boolean aLocked = aLockAcquired.await(10, TimeUnit.SECONDS);
                    assertThat(aLocked).isTrue();

                    bLockAttemptStarted.countDown();

                    // This SELECT FOR UPDATE must block until Transaction A commits
                    try (PreparedStatement lockStmtB =
                            connB.prepareStatement("SELECT counter FROM " + tableName + " WHERE id = ? FOR UPDATE")) {
                        lockStmtB.setString(1, "key_1");
                        try (ResultSet rsB = lockStmtB.executeQuery()) {
                            bLockAcquired.countDown();
                            assertThat(rsB.next()).isTrue();
                            // Must observe A's committed counter = 101
                            assertThat(rsB.getInt("counter")).isEqualTo(101);
                        }
                    }

                    try (PreparedStatement updateStmtB = connB.prepareStatement(
                            "UPDATE " + tableName + " SET counter = 200, locked_by = 'node_b' WHERE id = ?")) {
                        updateStmtB.setString(1, "key_1");
                        updateStmtB.executeUpdate();
                    }
                    connB.commit();
                } catch (Exception e) {
                    throw new RuntimeException("Transaction B failed during row lock serialization", e);
                }
            });

            // Main test: wait for B_LOCK_ATTEMPT_STARTED
            assertThat(bLockAttemptStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // While A still owns lock, prove B has NOT completed using Future.get(timeout) expecting TimeoutException
            assertThatThrownBy(() -> futureB.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(bLockAcquired.getCount())
                    .as("Transaction B must NOT acquire row lock while Transaction A holds it")
                    .isEqualTo(1);

            // Release A
            aReleaseSignal.countDown();

            // A commits; B must then complete successfully within a generous safety timeout
            futureA.get(10, TimeUnit.SECONDS);
            futureB.get(10, TimeUnit.SECONDS);

            // Verify final deterministic state
            try (Connection checkConn = database.connection();
                    Statement checkStmt = checkConn.createStatement();
                    ResultSet rs = checkStmt.executeQuery(
                            "SELECT counter, locked_by FROM " + tableName + " WHERE id = 'key_1'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("counter")).isEqualTo(200);
                assertThat(rs.getString("locked_by")).isEqualTo("node_b");
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
