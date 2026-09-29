package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V35_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V35: where each node says it is alive, which world it serves and how loaded it is.
 *
 * <p>A placement strategy chooses among these rows. The row is the node's own and never an
 * authority: nothing that reads it takes a lease.
 */
final class ClusterSchemaMigrationsV35 {

    private ClusterSchemaMigrationsV35() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(35, V35_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS cluster_nodes (
                        node_id TEXT NOT NULL PRIMARY KEY,
                        world_name TEXT NOT NULL,
                        hosted INTEGER NOT NULL,
                        capacity INTEGER NOT NULL,
                        average_mspt REAL NOT NULL,
                        last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                    CREATE INDEX IF NOT EXISTS idx_cluster_nodes_world ON cluster_nodes (world_name, last_seen);
                    """);
            case MYSQL -> new Migration(35, V35_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS cluster_nodes (
                        node_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        world_name VARCHAR(255) NOT NULL,
                        hosted INT NOT NULL,
                        capacity INT NOT NULL,
                        average_mspt DOUBLE NOT NULL,
                        last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_cluster_nodes_world (world_name, last_seen)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(35, V35_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS cluster_nodes (
                        node_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        world_name VARCHAR(255) NOT NULL,
                        hosted INTEGER NOT NULL,
                        capacity INTEGER NOT NULL,
                        average_mspt DOUBLE PRECISION NOT NULL,
                        last_seen TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                    CREATE INDEX IF NOT EXISTS idx_cluster_nodes_world ON cluster_nodes (world_name, last_seen);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V35: " + dialect);
        };
    }
}
