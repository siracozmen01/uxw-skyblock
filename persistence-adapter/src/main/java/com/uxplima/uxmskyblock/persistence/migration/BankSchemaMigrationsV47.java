package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V47_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V47: what an island bank holds of each currency the operator lists.
 *
 * <p>The bank kept the island's own money, crystals and experience in three columns, and an operator who wanted
 * players to keep their experience, their points or a stack of diamonds in it had nowhere to put them. Each such
 * currency is one row per island, in whole units under the id the operator gave it. A row appears the first time
 * an island holds any, and goes with the island.
 */
final class BankSchemaMigrationsV47 {

    private BankSchemaMigrationsV47() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(47, V47_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS island_bank_balances (
                        island_id TEXT NOT NULL,
                        currency_id TEXT NOT NULL,
                        balance INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, currency_id),
                        CONSTRAINT fk_island_bank_balances_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(47, V47_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS island_bank_balances (
                        island_id VARCHAR(36) NOT NULL,
                        currency_id VARCHAR(32) NOT NULL,
                        balance BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, currency_id),
                        CONSTRAINT fk_island_bank_balances_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(47, V47_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS island_bank_balances (
                        island_id VARCHAR(36) NOT NULL,
                        currency_id VARCHAR(32) NOT NULL,
                        balance BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, currency_id),
                        CONSTRAINT fk_island_bank_balances_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V47: " + dialect);
        };
    }
}
