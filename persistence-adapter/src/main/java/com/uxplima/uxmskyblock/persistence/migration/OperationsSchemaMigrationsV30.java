package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V30_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V30: the last upkeep period an island was charged for.
 *
 * <p>The upkeep cycle drew a fresh key every run, so a restart charged every island again at once
 * and a cluster charged every island once per node. The charge now carries one key per period, and
 * the period an island paid or owed is kept here so a second run owes nothing either.
 */
final class OperationsSchemaMigrationsV30 {

    private OperationsSchemaMigrationsV30() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> new Migration(30, V30_DESCRIPTION, """
                    ALTER TABLE island_bankruptcies
                        ADD COLUMN upkeep_period BIGINT NOT NULL DEFAULT -1;
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V30: " + dialect);
        };
    }
}
