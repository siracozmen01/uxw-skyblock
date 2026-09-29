package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V39_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/** V39: which islands are Boxed islands, and each advancement one has earned with what it was worth. */
final class GameModeSchemaMigrationsV39 {

    private GameModeSchemaMigrationsV39() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(39, V39_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_instance_state (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_boxed_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS boxed_advancements (
                        island_id TEXT NOT NULL,
                        advancement TEXT NOT NULL,
                        blocks INTEGER NOT NULL,
                        earned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, advancement),
                        CONSTRAINT fk_boxed_advancements_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(39, V39_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_instance_state (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_boxed_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS boxed_advancements (
                        island_id VARCHAR(36) NOT NULL,
                        advancement VARCHAR(128) NOT NULL,
                        blocks INT NOT NULL,
                        earned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, advancement),
                        CONSTRAINT fk_boxed_advancements_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(39, V39_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS boxed_instance_state (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_boxed_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS boxed_advancements (
                        island_id VARCHAR(36) NOT NULL,
                        advancement VARCHAR(128) NOT NULL,
                        blocks INTEGER NOT NULL,
                        earned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, advancement),
                        CONSTRAINT fk_boxed_advancements_state FOREIGN KEY (island_id)
                            REFERENCES boxed_instance_state (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V39: " + dialect);
        };
    }
}
