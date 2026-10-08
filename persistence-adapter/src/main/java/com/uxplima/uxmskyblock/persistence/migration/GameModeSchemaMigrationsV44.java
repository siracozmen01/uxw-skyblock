package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V44_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V44: which islands are TradeWinds vessels, with each vessel's cargo hold and the trade it has done,
 * and its standing in every port it trades at.
 *
 * <p>The hold is the vessel's, not any player's: its version moves with every change, and a move between
 * a player and the hold is journaled over both.
 */
final class GameModeSchemaMigrationsV44 {

    private GameModeSchemaMigrationsV44() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(44, V44_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_vessels (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        cargo BLOB NULL,
                        cargo_version INTEGER NOT NULL DEFAULT 1,
                        trade_volume INTEGER NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tradewinds_vessels_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS tradewinds_standing (
                        island_id TEXT NOT NULL,
                        port_id TEXT NOT NULL,
                        standing INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (island_id, port_id),
                        CONSTRAINT fk_tradewinds_standing_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(44, V44_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_vessels (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        cargo LONGBLOB NULL,
                        cargo_version BIGINT NOT NULL DEFAULT 1,
                        trade_volume BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tradewinds_vessels_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS tradewinds_standing (
                        island_id VARCHAR(36) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        standing BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (island_id, port_id),
                        CONSTRAINT fk_tradewinds_standing_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(44, V44_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_vessels (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        cargo BYTEA NULL,
                        cargo_version BIGINT NOT NULL DEFAULT 1,
                        trade_volume BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tradewinds_vessels_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS tradewinds_standing (
                        island_id VARCHAR(36) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        standing BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (island_id, port_id),
                        CONSTRAINT fk_tradewinds_standing_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V44: " + dialect);
        };
    }
}
