package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V34_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V34: what a lifecycle event still owes a player who was not here to be paid.
 *
 * <p>A player kicked from their island while offline, or while playing on another server, cannot
 * have their inventory emptied then. The effect is written here, one row per player and effect, and
 * paid when the player's next session is made, on any server.
 */
final class LifecycleSchemaMigrationsV34 {

    private LifecycleSchemaMigrationsV34() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(34, V34_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS lifecycle_owed_effects (
                        player_uuid TEXT NOT NULL,
                        effect TEXT NOT NULL,
                        owed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, effect)
                    );
                    """);
            case MYSQL -> new Migration(34, V34_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS lifecycle_owed_effects (
                        player_uuid VARCHAR(36) NOT NULL,
                        effect VARCHAR(32) NOT NULL,
                        owed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, effect)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(34, V34_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS lifecycle_owed_effects (
                        player_uuid VARCHAR(36) NOT NULL,
                        effect VARCHAR(32) NOT NULL,
                        owed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, effect)
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V34: " + dialect);
        };
    }
}
