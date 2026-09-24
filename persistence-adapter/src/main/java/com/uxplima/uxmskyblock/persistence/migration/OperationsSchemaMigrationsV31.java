package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V31_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V31: an account's active profile may be checked at the end of a transaction on PostgreSQL.
 *
 * <p>An account points at its active profile and every profile points at its account, so neither row
 * can be written first while both keys are checked at once. A whole-database restore writes both,
 * and on PostgreSQL, which cannot switch foreign keys off without a superuser, it could not restore at
 * all. The account's key is made deferrable, still checked at once unless a transaction asks otherwise;
 * SQLite and MariaDB switch their foreign keys off for a restore and need nothing.
 */
final class OperationsSchemaMigrationsV31 {

    private OperationsSchemaMigrationsV31() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case POSTGRES -> new Migration(31, V31_DESCRIPTION, """
                    ALTER TABLE player_accounts
                        ALTER CONSTRAINT fk_player_accounts_active_profile DEFERRABLE INITIALLY IMMEDIATE;
                    """);
            // Nothing to change here: a restore switches foreign keys off on this dialect. SQLite will not
            // run a statement that is only a comment, so the version is recorded over a statement that
            // changes nothing.
            case SQLITE, MYSQL -> new Migration(31, V31_DESCRIPTION, "SELECT 1;");
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V31: " + dialect);
        };
    }
}
