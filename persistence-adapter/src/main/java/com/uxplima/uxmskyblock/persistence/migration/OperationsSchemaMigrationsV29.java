package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V29_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V29: whether a player still owes the inventory purge their island reset asked for.
 *
 * <p>The purge ran on the player's own thread when the erasure finished, so a player who left
 * while their island was erased kept everything they carried. What is owed is kept here and paid
 * when their next session is made, on whichever server that is.
 */
final class OperationsSchemaMigrationsV29 {

    private OperationsSchemaMigrationsV29() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(29, V29_DESCRIPTION, """
                    ALTER TABLE player_anti_abuse_records
                        ADD COLUMN inventory_purge_owed BOOLEAN NOT NULL DEFAULT 0;
                    """);
            case MYSQL, POSTGRES -> new Migration(29, V29_DESCRIPTION, """
                    ALTER TABLE player_anti_abuse_records
                        ADD COLUMN inventory_purge_owed BOOLEAN NOT NULL DEFAULT FALSE;
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V29: " + dialect);
        };
    }
}
