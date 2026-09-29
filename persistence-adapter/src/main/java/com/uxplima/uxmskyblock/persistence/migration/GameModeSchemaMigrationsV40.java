package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V40_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/** V40: which islands are Poseidon islands. */
final class GameModeSchemaMigrationsV40 {

    private GameModeSchemaMigrationsV40() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(40, V40_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS poseidon_instance_state (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_poseidon_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(40, V40_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS poseidon_instance_state (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_poseidon_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(40, V40_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS poseidon_instance_state (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_poseidon_state_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V40: " + dialect);
        };
    }
}
