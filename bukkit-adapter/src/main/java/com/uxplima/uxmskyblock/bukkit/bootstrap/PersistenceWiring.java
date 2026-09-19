package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * Encapsulates persistence layer initialization, connection pool lifecycle,
 * and database driver selection.
 */
public final class PersistenceWiring implements AutoCloseable {

    private final PersistenceBootstrap persistenceBootstrap;

    public PersistenceWiring(PersistenceBootstrap persistenceBootstrap) {
        this.persistenceBootstrap =
                Objects.requireNonNull(persistenceBootstrap, "persistenceBootstrap must not be null");
    }

    /**
     * Resolves the database backend from configuration or environment overrides.
     */
    public static PersistenceWiring resolve(@Nullable CommentedConfigurationNode rootNode, Path dataDir) {
        String envJdbc = System.getProperty("skyblock.jdbc.url", System.getenv("SKYBLOCK_JDBC_URL"));
        String envUser = System.getProperty("skyblock.db.user", System.getenv("SKYBLOCK_DB_USER"));
        String envPass = System.getProperty("skyblock.db.password", System.getenv("SKYBLOCK_DB_PASSWORD"));

        if (envJdbc != null && !envJdbc.isBlank()) {
            return new PersistenceWiring(PersistenceBootstrap.createRemote(envJdbc.trim(), envUser, envPass, 10));
        }

        if (rootNode != null) {
            CommentedConfigurationNode dbNode = rootNode.node("database");
            String dbType = dbNode.node("type").getString("sqlite");
            if ("remote".equalsIgnoreCase(dbType)
                    || "mysql".equalsIgnoreCase(dbType)
                    || "postgresql".equalsIgnoreCase(dbType)) {
                String jdbcUrl = dbNode.node("jdbc-url").getString();
                if (jdbcUrl != null && !jdbcUrl.isBlank()) {
                    String user = dbNode.node("username").getString("");
                    String pass = dbNode.node("password").getString("");
                    int poolSize = dbNode.node("max-pool-size").getInt(10);
                    return new PersistenceWiring(
                            PersistenceBootstrap.createRemote(jdbcUrl.trim(), user, pass, poolSize));
                }
            }
        }

        Path dbFile = dataDir.resolve("skyblock.db");
        return new PersistenceWiring(PersistenceBootstrap.createSqlite(dbFile));
    }

    public PersistenceBootstrap bootstrap() {
        return persistenceBootstrap;
    }

    @Override
    public void close() {
        persistenceBootstrap.close();
    }
}
