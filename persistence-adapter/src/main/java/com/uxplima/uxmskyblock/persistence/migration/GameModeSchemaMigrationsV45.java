package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V45_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V45: where each TradeWinds vessel is bound and when it arrives, and the orders its crew placed at the
 * ports' markets.
 *
 * <p>An order is a saga between the vessel's hold and the island bank. Its state and its attempts are
 * written down before the bank is asked, so an order a crash cut short is carried on, never repeated.
 */
final class GameModeSchemaMigrationsV45 {

    private GameModeSchemaMigrationsV45() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(45, V45_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_voyages (
                        island_id TEXT NOT NULL PRIMARY KEY,
                        port_id TEXT NOT NULL,
                        arrives_at TIMESTAMP NOT NULL,
                        CONSTRAINT fk_tradewinds_voyages_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS tradewinds_orders (
                        order_id TEXT NOT NULL PRIMARY KEY,
                        island_id TEXT NOT NULL,
                        port_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        item TEXT NOT NULL,
                        item_count INTEGER NOT NULL,
                        amount INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tradewinds_orders_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_orders_open ON tradewinds_orders (state, island_id);
                    """);
            case MYSQL -> new Migration(45, V45_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_voyages (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        port_id VARCHAR(64) NOT NULL,
                        arrives_at TIMESTAMP NOT NULL,
                        CONSTRAINT fk_tradewinds_voyages_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS tradewinds_orders (
                        order_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        island_id VARCHAR(36) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        item VARCHAR(64) NOT NULL,
                        item_count INT NOT NULL,
                        amount BIGINT NOT NULL,
                        actor_uuid VARCHAR(36) NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        attempts INT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tradewinds_orders_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE INDEX idx_tradewinds_orders_open ON tradewinds_orders (state, island_id);
                    """);
            case POSTGRES -> new Migration(45, V45_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS tradewinds_voyages (
                        island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        port_id VARCHAR(64) NOT NULL,
                        arrives_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        CONSTRAINT fk_tradewinds_voyages_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE TABLE IF NOT EXISTS tradewinds_orders (
                        order_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        island_id VARCHAR(36) NOT NULL,
                        port_id VARCHAR(64) NOT NULL,
                        kind VARCHAR(16) NOT NULL,
                        item VARCHAR(64) NOT NULL,
                        item_count INTEGER NOT NULL,
                        amount BIGINT NOT NULL,
                        actor_uuid VARCHAR(36) NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_tradewinds_orders_vessel FOREIGN KEY (island_id)
                            REFERENCES tradewinds_vessels (island_id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_tradewinds_orders_open ON tradewinds_orders (state, island_id);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V45: " + dialect);
        };
    }
}
