package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V42_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/** V42: which islands are Parkour courses, and each runner's best time on each. */
final class GameModeSchemaMigrationsV42 {

    private GameModeSchemaMigrationsV42() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(42, V42_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_courses (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_parkour_courses_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS parkour_records (
                        island_id TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        best_millis INTEGER NOT NULL,
                        runs INTEGER NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_records_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(42, V42_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_courses (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_parkour_courses_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS parkour_records (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        best_millis BIGINT NOT NULL,
                        runs INT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_records_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(42, V42_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS parkour_courses (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_parkour_courses_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS parkour_records (
                        island_id VARCHAR(36) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        best_millis BIGINT NOT NULL,
                        runs INT NOT NULL,
                        PRIMARY KEY (island_id, player_uuid),
                        CONSTRAINT fk_parkour_records_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V42: " + dialect);
        };
    }
}
