package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V33_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V33: where a OneBlock island's block stands and how many times it has been broken.
 *
 * <p>A row here is what makes an island a OneBlock island. The count is the island's whole life, and
 * the phase it has reached is worked out from it, so a phase an operator adds or lengthens later moves
 * every island by the same rule. The row goes with its island.
 */
final class GameModeSchemaMigrationsV33 {

    private GameModeSchemaMigrationsV33() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(33, V33_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_progress (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        blocks_broken INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_oneblock_progress_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(33, V33_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_progress (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        block_x INT NOT NULL,
                        block_y INT NOT NULL,
                        block_z INT NOT NULL,
                        blocks_broken BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_oneblock_progress_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(33, V33_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS oneblock_progress (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        block_x INTEGER NOT NULL,
                        block_y INTEGER NOT NULL,
                        block_z INTEGER NOT NULL,
                        blocks_broken BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_oneblock_progress_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V33: " + dialect);
        };
    }
}
