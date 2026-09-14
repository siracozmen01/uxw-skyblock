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

    /** Creates a configured, unstarted PostgreSQL container instance. */
    public static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("testdb")
                .withUsername("testuser")
                .withPassword("testpass");
    }

    /** Connects a uxmlib Database pool to an active Testcontainers JDBC database container. */
    public static Database connectToContainer(JdbcDatabaseContainer<?> container, Dialect dialect) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(dialect, "dialect");
        return Database.builder()
                .jdbcUrl(container.getJdbcUrl())
                .username(container.getUsername())
                .password(container.getPassword())
                .maxPoolSize(5)
                .build();
    }
}
