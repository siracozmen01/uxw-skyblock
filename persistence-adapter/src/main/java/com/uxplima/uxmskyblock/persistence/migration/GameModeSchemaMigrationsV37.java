package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V37_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/** V37: which islands are AcidIsland islands, and the level their acid sea's surface stands at. */
final class GameModeSchemaMigrationsV37 {

    private GameModeSchemaMigrationsV37() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(37, V37_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS acid_island_state (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        sea_level INTEGER NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_acid_island_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(37, V37_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS acid_island_state (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        sea_level INT NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_acid_island_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(37, V37_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS acid_island_state (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        sea_level INTEGER NOT NULL,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_acid_island_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V37: " + dialect);
        };
    }
}
