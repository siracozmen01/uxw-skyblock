package com.uxplima.uxmskyblock.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfMariaDb;
import com.uxplima.uxmskyblock.persistence.testfixture.EnabledIfPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A server database set to acknowledge commits before they reach the disk is caught at startup.
 *
 * <p>The testing standard's scenario: MariaDB with {@code innodb_flush_log_at_trx_commit} other than 1,
 * or PostgreSQL with {@code synchronous_commit = off}. The engines ship durable, so each test first
 * reads a clean default and then turns the setting off.
 */
@Tag("database-integration")
@Execution(ExecutionMode.SAME_THREAD)
@SuppressWarnings("NullAway")
class DatabaseNonDurableConfigurationDetectionTest {

    private static MariaDBContainer<?> mariaDbContainer;
    private static PostgreSQLContainer<?> postgresContainer;

    @BeforeAll
    static void setUpAll() {
        mariaDbContainer = DatabaseTestFixture.startMariaDbIfEnabled();
        if (DatabaseTestFixture.isPostgresEnabled()) {
            // The test image turns fsync off to run faster, which is exactly what this check refuses.
            // A durable server is started here, the way a production one runs.
            postgresContainer = DatabaseTestFixture.newPostgresContainer().withCommand("postgres", "-c", "fsync=on");
            postgresContainer.start();
        }
    }

    @AfterAll
    static void tearDownAll() {
        if (mariaDbContainer != null) {
            mariaDbContainer.stop();
        }
        if (postgresContainer != null) {
            postgresContainer.stop();
        }
    }

    @Test
    @EnabledIfMariaDb
    @DisplayName("MariaDB: the shipped settings pass, and a lazy redo log flush is refused under strict")
    void mariaLazyFlushIsCaught() throws Exception {
        Database database = DatabaseTestFixture.connectToContainer(mariaDbContainer, Dialect.MYSQL);
        try {
            assertThat(DurabilityCheck.findProblems(database)).isEmpty();

            try (Connection root = DriverManager.getConnection(
                            mariaDbContainer.getJdbcUrl(), "root", mariaDbContainer.getPassword());
                    Statement stmt = root.createStatement()) {
                stmt.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 2");
                try {
                    assertThat(DurabilityCheck.findProblems(database))
                            .singleElement()
                            .asString()
                            .contains("innodb_flush_log_at_trx_commit is 2");
                    assertThatThrownBy(
                                    () -> DurabilityCheck.enforce(database, DurabilityCheck.Profile.PRODUCTION_STRICT))
                            .isInstanceOf(FatalDurabilityConfigurationException.class);
                } finally {
                    stmt.execute("SET GLOBAL innodb_flush_log_at_trx_commit = 1");
                }
            }
        } finally {
            database.close();
        }
    }

    @Test
    @EnabledIfPostgres
    @DisplayName("PostgreSQL: the shipped settings pass, and synchronous_commit off is refused under strict")
    void postgresAsyncCommitIsCaught() throws Exception {
        Database shipped = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
        try {
            assertThat(DurabilityCheck.findProblems(shipped)).isEmpty();
        } finally {
            shipped.close();
        }

        try (Connection admin = DriverManager.getConnection(
                        postgresContainer.getJdbcUrl(),
                        postgresContainer.getUsername(),
                        postgresContainer.getPassword());
                Statement stmt = admin.createStatement()) {
            stmt.execute("ALTER DATABASE " + postgresContainer.getDatabaseName() + " SET synchronous_commit = off");
            Database lazy = DatabaseTestFixture.connectToContainer(postgresContainer, Dialect.POSTGRES);
            try {
                assertThat(DurabilityCheck.findProblems(lazy))
                        .singleElement()
                        .asString()
                        .contains("synchronous_commit is off");
                assertThatThrownBy(() -> DurabilityCheck.enforce(lazy, DurabilityCheck.Profile.PRODUCTION_STRICT))
                        .isInstanceOf(FatalDurabilityConfigurationException.class);
            } finally {
                lazy.close();
                stmt.execute("ALTER DATABASE " + postgresContainer.getDatabaseName() + " RESET synchronous_commit");
            }
        }
    }
}
