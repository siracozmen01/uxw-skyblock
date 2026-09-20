package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V7_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V8_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V9_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class CoreSchemaMigrationsV7ToV9 {

    private CoreSchemaMigrationsV7ToV9() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v7Migration(dialect), v8Migration(dialect), v9Migration(dialect));
    }

    private static Migration v7Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(7, V7_DESCRIPTION, SQLITE_V7_DDL);
            case MYSQL -> new Migration(7, V7_DESCRIPTION, MYSQL_V7_DDL);
            case POSTGRES -> new Migration(7, V7_DESCRIPTION, POSTGRES_V7_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v8Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(8, V8_DESCRIPTION, SQLITE_V8_DDL);
            case MYSQL -> new Migration(8, V8_DESCRIPTION, MYSQL_V8_DDL);
            case POSTGRES -> new Migration(8, V8_DESCRIPTION, POSTGRES_V8_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v9Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(9, V9_DESCRIPTION, SQLITE_V9_DDL);
            case MYSQL -> new Migration(9, V9_DESCRIPTION, MYSQL_V9_DDL);
            case POSTGRES -> new Migration(9, V9_DESCRIPTION, POSTGRES_V9_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static final String SQLITE_V7_DDL = """
            CREATE TABLE IF NOT EXISTS island_upgrades (
                island_id VARCHAR(36) NOT NULL,
                upgrade_key VARCHAR(64) NOT NULL,
                tier INT NOT NULL DEFAULT 0,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, upgrade_key),
                CONSTRAINT fk_island_upgrades_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_islands_worth ON islands (net_worth_minor_units DESC);
            CREATE INDEX IF NOT EXISTS idx_island_banks_balance ON island_banks (primary_balance_minor_units DESC);
            """;

    private static final String MYSQL_V7_DDL = """
            CREATE TABLE IF NOT EXISTS island_upgrades (
                island_id VARCHAR(36) NOT NULL,
                upgrade_key VARCHAR(64) NOT NULL,
                tier INT NOT NULL DEFAULT 0,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, upgrade_key),
                CONSTRAINT fk_island_upgrades_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_islands_worth ON islands (net_worth_minor_units DESC);
            CREATE INDEX idx_island_banks_balance ON island_banks (primary_balance_minor_units DESC);
            """;

    private static final String POSTGRES_V7_DDL = """
            CREATE TABLE IF NOT EXISTS island_upgrades (
                island_id VARCHAR(36) NOT NULL,
                upgrade_key VARCHAR(64) NOT NULL,
                tier INT NOT NULL DEFAULT 0,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, upgrade_key),
                CONSTRAINT fk_island_upgrades_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_islands_worth ON islands (net_worth_minor_units DESC);
            CREATE INDEX idx_island_banks_balance ON island_banks (primary_balance_minor_units DESC);
            """;

    private static final String SQLITE_V8_DDL = """
            CREATE TABLE IF NOT EXISTS backup_operations (
                backup_set_id VARCHAR(36) NOT NULL PRIMARY KEY,
                backup_type VARCHAR(32) NOT NULL,
                target_root_type_id VARCHAR(64) NULL,
                target_root_key VARCHAR(64) NULL,
                state VARCHAR(32) NOT NULL,
                authority_epoch BIGINT NOT NULL,
                db_version BIGINT NOT NULL,
                schema_version INT NOT NULL,
                plugin_version VARCHAR(32) NOT NULL,
                failure_reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_backup_ops_root ON backup_operations (target_root_type_id, target_root_key, state);
            CREATE INDEX IF NOT EXISTS idx_backup_ops_state ON backup_operations (state);
            """;

    private static final String MYSQL_V8_DDL = """
            CREATE TABLE IF NOT EXISTS backup_operations (
                backup_set_id VARCHAR(36) NOT NULL PRIMARY KEY,
                backup_type VARCHAR(32) NOT NULL,
                target_root_type_id VARCHAR(64) NULL,
                target_root_key VARCHAR(64) NULL,
                state VARCHAR(32) NOT NULL,
                authority_epoch BIGINT NOT NULL,
                db_version BIGINT NOT NULL,
                schema_version INT NOT NULL,
                plugin_version VARCHAR(32) NOT NULL,
                failure_reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_backup_ops_root ON backup_operations (target_root_type_id, target_root_key, state);
            CREATE INDEX idx_backup_ops_state ON backup_operations (state);
            """;

    private static final String POSTGRES_V8_DDL = """
            CREATE TABLE IF NOT EXISTS backup_operations (
                backup_set_id VARCHAR(36) NOT NULL PRIMARY KEY,
                backup_type VARCHAR(32) NOT NULL,
                target_root_type_id VARCHAR(64) NULL,
                target_root_key VARCHAR(64) NULL,
                state VARCHAR(32) NOT NULL,
                authority_epoch BIGINT NOT NULL,
                db_version BIGINT NOT NULL,
                schema_version INT NOT NULL,
                plugin_version VARCHAR(32) NOT NULL,
                failure_reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_backup_ops_root ON backup_operations (target_root_type_id, target_root_key, state);
            CREATE INDEX idx_backup_ops_state ON backup_operations (state);
            """;

    private static final String SQLITE_V9_DDL = """
            CREATE TABLE IF NOT EXISTS outbox_events (
                event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL,
                aggregate_id VARCHAR(36) NOT NULL,
                payload TEXT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                claim_owner VARCHAR(64) NULL,
                claim_token VARCHAR(36) NULL,
                claim_expires_at TIMESTAMP NULL,
                retry_count INT NOT NULL DEFAULT 0,
                next_attempt_at TIMESTAMP NULL,
                last_error TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_at TIMESTAMP NULL
            );

            CREATE INDEX IF NOT EXISTS idx_outbox_events_status_created ON outbox_events (status, next_attempt_at, created_at ASC);
            CREATE INDEX IF NOT EXISTS idx_outbox_events_claim ON outbox_events (status, claim_expires_at);

            CREATE TABLE IF NOT EXISTS consumer_inbox (
                consumer_name VARCHAR(64) NOT NULL,
                event_id VARCHAR(36) NOT NULL,
                processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (consumer_name, event_id)
            );

            CREATE INDEX IF NOT EXISTS idx_consumer_inbox_processed ON consumer_inbox (processed_at);
            """;

    private static final String MYSQL_V9_DDL = """
            CREATE TABLE IF NOT EXISTS outbox_events (
                event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL,
                aggregate_id VARCHAR(36) NOT NULL,
                payload TEXT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                claim_owner VARCHAR(64) NULL,
                claim_token VARCHAR(36) NULL,
                claim_expires_at TIMESTAMP NULL,
                retry_count INT NOT NULL DEFAULT 0,
                next_attempt_at TIMESTAMP NULL,
                last_error TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_at TIMESTAMP NULL
            );

            CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, next_attempt_at, created_at ASC);
            CREATE INDEX idx_outbox_events_claim ON outbox_events (status, claim_expires_at);

            CREATE TABLE IF NOT EXISTS consumer_inbox (
                consumer_name VARCHAR(64) NOT NULL,
                event_id VARCHAR(36) NOT NULL,
                processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (consumer_name, event_id)
            );

            CREATE INDEX idx_consumer_inbox_processed ON consumer_inbox (processed_at);
            """;

    private static final String POSTGRES_V9_DDL = """
            CREATE TABLE IF NOT EXISTS outbox_events (
                event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL,
                aggregate_id VARCHAR(36) NOT NULL,
                payload TEXT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                claim_owner VARCHAR(64) NULL,
                claim_token VARCHAR(36) NULL,
                claim_expires_at TIMESTAMP NULL,
                retry_count INT NOT NULL DEFAULT 0,
                next_attempt_at TIMESTAMP NULL,
                last_error TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                processed_at TIMESTAMP NULL
            );

            CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, next_attempt_at, created_at ASC);
            CREATE INDEX idx_outbox_events_claim ON outbox_events (status, claim_expires_at);

            CREATE TABLE IF NOT EXISTS consumer_inbox (
                consumer_name VARCHAR(64) NOT NULL,
                event_id VARCHAR(36) NOT NULL,
                processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (consumer_name, event_id)
            );

            CREATE INDEX idx_consumer_inbox_processed ON consumer_inbox (processed_at);
            """;
}
