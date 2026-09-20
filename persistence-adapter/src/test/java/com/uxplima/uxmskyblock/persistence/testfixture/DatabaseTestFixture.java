package com.uxplima.uxmskyblock.persistence.testfixture;

import java.nio.file.Path;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Reusable test-only fixture factory establishing database instances across supported dialects:
 * SQLite (fast local lane) and MariaDB / PostgreSQL (Testcontainers integration lane).
 *
 * <p>Strictly confined to {@code :persistence-adapter} test sources. Owns zero production concerns.
 */
public final class DatabaseTestFixture {

    /** Pinned, stable LTS MariaDB container image version. */
    public static final String MARIADB_IMAGE = "mariadb:10.11.11";

    /** Pinned, stable PostgreSQL container image version. */
    public static final String POSTGRES_IMAGE = "postgres:15.12-alpine";

    private DatabaseTestFixture() {}

    /** Checks whether MariaDB / MySQL integration tests are enabled for this test run. */
    public static boolean isMariaDbEnabled() {
        String db = System.getProperty("skyblock.test.database", "all").trim().toLowerCase(java.util.Locale.ROOT);
        return db.equals("all") || db.equals("mariadb") || db.equals("mysql");
    }

    /** Checks whether PostgreSQL integration tests are enabled for this test run. */
    public static boolean isPostgresEnabled() {
        String db = System.getProperty("skyblock.test.database", "all").trim().toLowerCase(java.util.Locale.ROOT);
        return db.equals("all") || db.equals("postgres") || db.equals("postgresql");
    }

    /** Creates an isolated file-backed SQLite database for testing. */
    public static Database createSqliteFile(Path dbFile) {
        Objects.requireNonNull(dbFile, "dbFile");
        return Database.builder().sqlite(dbFile).build();
    }

    /** Creates an in-memory SQLite database for testing. */
    public static Database createSqliteInMemory() {
        return Database.builder().sqliteInMemory().build();
    }

    /** Creates a configured, unstarted MariaDB container instance. */
    public static MariaDBContainer<?> newMariaDbContainer() {
        return new MariaDBContainer<>(MARIADB_IMAGE)
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass");
    }

    /** Starts a MariaDB container only if MariaDB tests are enabled; returns null otherwise. */
    public static MariaDBContainer<?> startMariaDbIfEnabled() {
        if (!isMariaDbEnabled()) {
            return null;
        }
        MariaDBContainer<?> container = newMariaDbContainer();
        container.start();
        return container;
    }

    /** Creates a configured, unstarted PostgreSQL container instance. */
    public static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass");
    }

    /** Starts a PostgreSQL container only if PostgreSQL tests are enabled; returns null otherwise. */
    public static PostgreSQLContainer<?> startPostgresIfEnabled() {
        if (!isPostgresEnabled()) {
            return null;
        }
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();
        return container;
    }

    /** Connects a uxmlib Database pool to an active Testcontainers JDBC database container. */
    public static Database connectToContainer(JdbcDatabaseContainer<?> container, Dialect dialect) {
        if (container == null) {
            return null;
        }
        Objects.requireNonNull(dialect, "dialect");
        return Database.builder()
                .jdbcUrl(container.getJdbcUrl())
                .username(container.getUsername())
                .password(container.getPassword())
                .maxPoolSize(5)
                .build();
    }

    /** Asserts that MariaDB integration tests are enabled, aborting the test cleanly if not. */
    public static void assumeMariaDb(Database database) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                database != null && isMariaDbEnabled(), "MariaDB integration tests are disabled for this execution");
    }

    /** Asserts that PostgreSQL integration tests are enabled, aborting the test cleanly if not. */
    public static void assumePostgres(Database database) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                database != null && isPostgresEnabled(),
                "PostgreSQL integration tests are disabled for this execution");
    }
}
