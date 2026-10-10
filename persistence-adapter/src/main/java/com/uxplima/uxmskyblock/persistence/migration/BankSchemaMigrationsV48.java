package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V48_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V48: where each move stands in its bank's history.
 *
 * <p>The history was read newest first by the time each move was written, and MariaDB and MySQL keep that time to
 * the second, so two moves in one second came back in either order: a deposit could read as made after the
 * withdrawal that followed it. A move now records the version its bank reached, which rises by exactly one with
 * every move, and the history is read by that. Moves written before this read zero and stand behind every later
 * one, in the order their times give.
 */
final class BankSchemaMigrationsV48 {

    private BankSchemaMigrationsV48() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> new Migration(48, V48_DESCRIPTION, """
                    ALTER TABLE bank_transactions
                        ADD COLUMN bank_version BIGINT NOT NULL DEFAULT 0;
                    CREATE INDEX idx_bank_transactions_island_version ON bank_transactions (island_id, bank_version);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V48: " + dialect);
        };
    }
}
