package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V32_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V32: a restore writes down each unit it is about to put back, and each one it put back.
 *
 * <p>The persistence specification publishes {@code restore_operations} and
 * {@code restore_unit_progress} for this, and neither table existed: a server that stopped halfway
 * through a restore kept no trace of it, left the island half put back, and let players onto it.
 *
 * <p>Two things differ from the published shape. The operation keeps the prefix its backup set was
 * read from, because the node that picks an interrupted restore up has nothing else to find the set
 * by. And the island is not a foreign key: a restore can bring back an island whose row is gone, and
 * a restore that could not be recorded for that reason would be one that could not be resumed.
 */
final class OperationsSchemaMigrationsV32 {

    private OperationsSchemaMigrationsV32() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(32, V32_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS restore_operations (
                        restore_id TEXT NOT NULL PRIMARY KEY,
                        island_id TEXT NOT NULL,
                        snapshot_id TEXT NOT NULL,
                        source_prefix TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        state TEXT NOT NULL DEFAULT 'PREPARING',
                        generation INTEGER NOT NULL DEFAULT 1,
                        started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_completed_unit TEXT NULL,
                        failure_reason TEXT NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_restore_operations_island ON restore_operations (island_id, state);
                    CREATE TABLE IF NOT EXISTS restore_unit_progress (
                        restore_id TEXT NOT NULL,
                        unit_id TEXT NOT NULL,
                        generation INTEGER NOT NULL DEFAULT 1,
                        state TEXT NOT NULL DEFAULT 'PENDING',
                        source_checksum TEXT NOT NULL,
                        started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMP NULL,
                        PRIMARY KEY (restore_id, unit_id),
                        CONSTRAINT fk_restore_progress_op FOREIGN KEY (restore_id)
                            REFERENCES restore_operations (restore_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_restore_unit_progress_lookup ON restore_unit_progress (restore_id, state);
                    """);
            case MYSQL -> new Migration(32, V32_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS restore_operations (
                        restore_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        island_id VARCHAR(36) NOT NULL,
                        snapshot_id VARCHAR(36) NOT NULL,
                        source_prefix VARCHAR(255) NOT NULL,
                        mode VARCHAR(32) NOT NULL,
                        state VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
                        generation BIGINT NOT NULL DEFAULT 1,
                        started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_completed_unit VARCHAR(64) NULL,
                        failure_reason VARCHAR(255) NULL,
                        INDEX idx_restore_operations_island (island_id, state)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS restore_unit_progress (
                        restore_id VARCHAR(36) NOT NULL,
                        unit_id VARCHAR(64) NOT NULL,
                        generation BIGINT NOT NULL DEFAULT 1,
                        state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                        source_checksum VARCHAR(64) NOT NULL,
                        started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMP NULL,
                        PRIMARY KEY (restore_id, unit_id),
                        INDEX idx_restore_unit_progress_lookup (restore_id, state),
                        CONSTRAINT fk_restore_progress_op FOREIGN KEY (restore_id)
                            REFERENCES restore_operations (restore_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(32, V32_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS restore_operations (
                        restore_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        island_id VARCHAR(36) NOT NULL,
                        snapshot_id VARCHAR(36) NOT NULL,
                        source_prefix VARCHAR(255) NOT NULL,
                        mode VARCHAR(32) NOT NULL,
                        state VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
                        generation BIGINT NOT NULL DEFAULT 1,
                        started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_completed_unit VARCHAR(64) NULL,
                        failure_reason VARCHAR(255) NULL
                    );
                    CREATE INDEX IF NOT EXISTS idx_restore_operations_island ON restore_operations (island_id, state);
                    CREATE TABLE IF NOT EXISTS restore_unit_progress (
                        restore_id VARCHAR(36) NOT NULL,
                        unit_id VARCHAR(64) NOT NULL,
                        generation BIGINT NOT NULL DEFAULT 1,
                        state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                        source_checksum VARCHAR(64) NOT NULL,
                        started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        completed_at TIMESTAMP WITH TIME ZONE NULL,
                        PRIMARY KEY (restore_id, unit_id),
                        CONSTRAINT fk_restore_progress_op FOREIGN KEY (restore_id)
                            REFERENCES restore_operations (restore_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_restore_unit_progress_lookup ON restore_unit_progress (restore_id, state);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V32: " + dialect);
        };
    }
}
