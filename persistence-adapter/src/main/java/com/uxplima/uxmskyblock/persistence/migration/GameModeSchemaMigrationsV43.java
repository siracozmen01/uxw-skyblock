package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V43_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/** V43: which islands are Brix plots. */
final class GameModeSchemaMigrationsV43 {

    private GameModeSchemaMigrationsV43() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(43, V43_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_plots (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_brix_plots_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(43, V43_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_plots (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_brix_plots_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(43, V43_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS brix_plots (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_brix_plots_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V43: " + dialect);
        };
    }
}
