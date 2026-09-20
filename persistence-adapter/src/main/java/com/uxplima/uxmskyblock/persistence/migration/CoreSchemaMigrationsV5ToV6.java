package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V5_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V6_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class CoreSchemaMigrationsV5ToV6 {

    private CoreSchemaMigrationsV5ToV6() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v5Migration(dialect), v6Migration(dialect));
    }

    private static Migration v5Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(5, V5_DESCRIPTION, SQLITE_V5_DDL);
            case MYSQL -> new Migration(5, V5_DESCRIPTION, MYSQL_V5_DDL);
            case POSTGRES -> new Migration(5, V5_DESCRIPTION, POSTGRES_V5_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v6Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(6, V6_DESCRIPTION, SQLITE_V6_DDL);
            case MYSQL -> new Migration(6, V6_DESCRIPTION, MYSQL_V6_DDL);
            case POSTGRES -> new Migration(6, V6_DESCRIPTION, POSTGRES_V6_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static final String SQLITE_V5_DDL = """
            CREATE TABLE IF NOT EXISTS islands (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_profile_id VARCHAR(36) NOT NULL,
                owner_account_uuid VARCHAR(36) NOT NULL,
                custom_name VARCHAR(32) NULL,
                lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                economic_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                administrative_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                freeze_reason VARCHAR(255) NULL,
                level_score BIGINT NOT NULL DEFAULT 0,
                net_worth_minor_units BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_islands_owner_profile ON islands (owner_profile_id);
            CREATE INDEX IF NOT EXISTS idx_islands_owner_account ON islands (owner_account_uuid);
            CREATE INDEX IF NOT EXISTS idx_islands_lifecycle ON islands (lifecycle);
            CREATE INDEX IF NOT EXISTS idx_islands_level ON islands (level_score DESC);

            CREATE TABLE IF NOT EXISTS island_authorities (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                authoritative_node VARCHAR(64) NOT NULL,
                authority_epoch BIGINT NOT NULL DEFAULT 1,
                lease_expires_at TIMESTAMP NOT NULL,
                last_heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_authorities_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_authorities_lease ON island_authorities (lease_expires_at ASC);
            CREATE INDEX IF NOT EXISTS idx_island_authorities_node ON island_authorities (authoritative_node);

            CREATE TABLE IF NOT EXISTS island_locations (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                center_x INT NOT NULL,
                center_z INT NOT NULL,
                min_x INT NOT NULL,
                min_z INT NOT NULL,
                max_x INT NOT NULL,
                max_z INT NOT NULL,
                spawn_x DOUBLE NOT NULL,
                spawn_y DOUBLE NOT NULL,
                spawn_z DOUBLE NOT NULL,
                spawn_yaw FLOAT NOT NULL DEFAULT 0.0,
                spawn_pitch FLOAT NOT NULL DEFAULT 0.0,
                CONSTRAINT fk_island_locations_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_locations_coords ON island_locations (world_name, center_x, center_z);

            CREATE TABLE IF NOT EXISTS island_members (
                island_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, profile_id),
                CONSTRAINT fk_island_members_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_members_profile ON island_members (profile_id);

            CREATE TABLE IF NOT EXISTS island_roles (
                island_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                weight INT NOT NULL,
                display_name VARCHAR(64) NOT NULL,
                is_system BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (island_id, role_id),
                CONSTRAINT fk_island_roles_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS island_role_permissions (
                island_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                permission VARCHAR(64) NOT NULL,
                PRIMARY KEY (island_id, role_id, permission),
                CONSTRAINT fk_island_role_perms FOREIGN KEY (island_id, role_id)
                    REFERENCES island_roles (island_id, role_id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS island_flags (
                island_id VARCHAR(36) NOT NULL,
                flag_name VARCHAR(64) NOT NULL,
                flag_value BOOLEAN NOT NULL,
                PRIMARY KEY (island_id, flag_name),
                CONSTRAINT fk_island_flags_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );
            """;

    private static final String MYSQL_V5_DDL = """
            CREATE TABLE IF NOT EXISTS islands (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_profile_id VARCHAR(36) NOT NULL,
                owner_account_uuid VARCHAR(36) NOT NULL,
                custom_name VARCHAR(32) NULL,
                lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                economic_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                administrative_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                freeze_reason VARCHAR(255) NULL,
                level_score BIGINT NOT NULL DEFAULT 0,
                net_worth_minor_units BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_islands_owner_profile ON islands (owner_profile_id);
            CREATE INDEX idx_islands_owner_account ON islands (owner_account_uuid);
            CREATE INDEX idx_islands_lifecycle ON islands (lifecycle);
            CREATE INDEX idx_islands_level ON islands (level_score DESC);

            CREATE TABLE IF NOT EXISTS island_authorities (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                authoritative_node VARCHAR(64) NOT NULL,
                authority_epoch BIGINT NOT NULL DEFAULT 1,
                lease_expires_at TIMESTAMP NOT NULL,
                last_heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_authorities_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_authorities_lease ON island_authorities (lease_expires_at ASC);
            CREATE INDEX idx_island_authorities_node ON island_authorities (authoritative_node);

            CREATE TABLE IF NOT EXISTS island_locations (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                center_x INT NOT NULL,
                center_z INT NOT NULL,
                min_x INT NOT NULL,
                min_z INT NOT NULL,
                max_x INT NOT NULL,
                max_z INT NOT NULL,
                spawn_x DOUBLE NOT NULL,
                spawn_y DOUBLE NOT NULL,
                spawn_z DOUBLE NOT NULL,
                spawn_yaw FLOAT NOT NULL DEFAULT 0.0,
                spawn_pitch FLOAT NOT NULL DEFAULT 0.0,
                CONSTRAINT fk_island_locations_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_locations_coords ON island_locations (world_name, center_x, center_z);

            CREATE TABLE IF NOT EXISTS island_members (
                island_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, profile_id),
                CONSTRAINT fk_island_members_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_members_profile ON island_members (profile_id);

            CREATE TABLE IF NOT EXISTS island_roles (
                island_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                weight INT NOT NULL,
                display_name VARCHAR(64) NOT NULL,
                is_system BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (island_id, role_id),
                CONSTRAINT fk_island_roles_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS island_role_permissions (
                island_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                permission VARCHAR(64) NOT NULL,
                PRIMARY KEY (island_id, role_id, permission),
                CONSTRAINT fk_island_role_perms FOREIGN KEY (island_id, role_id)
                    REFERENCES island_roles (island_id, role_id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS island_flags (
                island_id VARCHAR(36) NOT NULL,
                flag_name VARCHAR(64) NOT NULL,
                flag_value BOOLEAN NOT NULL,
                PRIMARY KEY (island_id, flag_name),
                CONSTRAINT fk_island_flags_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );
            """;

    private static final String POSTGRES_V5_DDL = """
            CREATE TABLE IF NOT EXISTS islands (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_profile_id VARCHAR(36) NOT NULL,
                owner_account_uuid VARCHAR(36) NOT NULL,
                custom_name VARCHAR(32) NULL,
                lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                economic_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                administrative_state VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
                freeze_reason VARCHAR(255) NULL,
                level_score BIGINT NOT NULL DEFAULT 0,
                net_worth_minor_units BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_islands_owner_profile ON islands (owner_profile_id);
            CREATE INDEX idx_islands_owner_account ON islands (owner_account_uuid);
            CREATE INDEX idx_islands_lifecycle ON islands (lifecycle);
            CREATE INDEX idx_islands_level ON islands (level_score DESC);

            CREATE TABLE IF NOT EXISTS island_authorities (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                authoritative_node VARCHAR(64) NOT NULL,
                authority_epoch BIGINT NOT NULL DEFAULT 1,
                lease_expires_at TIMESTAMP NOT NULL,
                last_heartbeat_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_authorities_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_authorities_lease ON island_authorities (lease_expires_at ASC);
            CREATE INDEX idx_island_authorities_node ON island_authorities (authoritative_node);

            CREATE TABLE IF NOT EXISTS island_locations (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                center_x INT NOT NULL,
                center_z INT NOT NULL,
                min_x INT NOT NULL,
                min_z INT NOT NULL,
                max_x INT NOT NULL,
                max_z INT NOT NULL,
                spawn_x DOUBLE PRECISION NOT NULL,
                spawn_y DOUBLE PRECISION NOT NULL,
                spawn_z DOUBLE PRECISION NOT NULL,
                spawn_yaw REAL NOT NULL DEFAULT 0.0,
                spawn_pitch REAL NOT NULL DEFAULT 0.0,
                CONSTRAINT fk_island_locations_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_locations_coords ON island_locations (world_name, center_x, center_z);

            CREATE TABLE IF NOT EXISTS island_members (
                island_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, profile_id),
                CONSTRAINT fk_island_members_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_members_profile ON island_members (profile_id);

            CREATE TABLE IF NOT EXISTS island_roles (
                island_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                weight INT NOT NULL,
                display_name VARCHAR(64) NOT NULL,
                is_system BOOLEAN NOT NULL DEFAULT FALSE,
                PRIMARY KEY (island_id, role_id),
                CONSTRAINT fk_island_roles_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS island_role_permissions (
                island_id VARCHAR(36) NOT NULL,
                role_id VARCHAR(32) NOT NULL,
                permission VARCHAR(64) NOT NULL,
                PRIMARY KEY (island_id, role_id, permission),
                CONSTRAINT fk_island_role_perms FOREIGN KEY (island_id, role_id)
                    REFERENCES island_roles (island_id, role_id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS island_flags (
                island_id VARCHAR(36) NOT NULL,
                flag_name VARCHAR(64) NOT NULL,
                flag_value BOOLEAN NOT NULL,
                PRIMARY KEY (island_id, flag_name),
                CONSTRAINT fk_island_flags_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );
            """;

    private static final String SQLITE_V6_DDL = """
            CREATE TABLE IF NOT EXISTS island_banks (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                primary_balance_minor_units BIGINT NOT NULL DEFAULT 0,
                crystals_balance BIGINT NOT NULL DEFAULT 0,
                exp_balance BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_banks_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS bank_transactions (
                transaction_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                actor_uuid VARCHAR(36) NOT NULL,
                currency_id VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
                currency_scale INT NOT NULL DEFAULT 2,
                delta_amount_minor_units BIGINT NOT NULL,
                resulting_balance_minor_units BIGINT NOT NULL,
                reason VARCHAR(64) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_bank_transactions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_bank_transactions_island_date ON bank_transactions (island_id, created_at DESC);
            CREATE INDEX IF NOT EXISTS idx_bank_transactions_operation ON bank_transactions (operation_id);

            CREATE TABLE IF NOT EXISTS processed_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_scope VARCHAR(32) NOT NULL,
                actor_id VARCHAR(36) NOT NULL,
                idempotency_key VARCHAR(64) NOT NULL,
                operation_type VARCHAR(64) NOT NULL,
                resource_id VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                result_code VARCHAR(64) NULL,
                result_payload TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                CONSTRAINT uq_processed_ops UNIQUE (operation_scope, actor_id, idempotency_key)
            );

            CREATE INDEX IF NOT EXISTS idx_processed_operations_resource ON processed_operations (resource_id);
            """;

    private static final String MYSQL_V6_DDL = """
            CREATE TABLE IF NOT EXISTS island_banks (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                primary_balance_minor_units BIGINT NOT NULL DEFAULT 0,
                crystals_balance BIGINT NOT NULL DEFAULT 0,
                exp_balance BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_banks_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS bank_transactions (
                transaction_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                actor_uuid VARCHAR(36) NOT NULL,
                currency_id VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
                currency_scale INT NOT NULL DEFAULT 2,
                delta_amount_minor_units BIGINT NOT NULL,
                resulting_balance_minor_units BIGINT NOT NULL,
                reason VARCHAR(64) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_bank_transactions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_bank_transactions_island_date ON bank_transactions (island_id, created_at DESC);
            CREATE INDEX idx_bank_transactions_operation ON bank_transactions (operation_id);

            CREATE TABLE IF NOT EXISTS processed_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_scope VARCHAR(32) NOT NULL,
                actor_id VARCHAR(36) NOT NULL,
                idempotency_key VARCHAR(64) NOT NULL,
                operation_type VARCHAR(64) NOT NULL,
                resource_id VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                result_code VARCHAR(64) NULL,
                result_payload JSON NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                CONSTRAINT uq_processed_ops UNIQUE (operation_scope, actor_id, idempotency_key)
            );

            CREATE INDEX idx_processed_operations_resource ON processed_operations (resource_id);
            """;

    private static final String POSTGRES_V6_DDL = """
            CREATE TABLE IF NOT EXISTS island_banks (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                primary_balance_minor_units BIGINT NOT NULL DEFAULT 0,
                crystals_balance BIGINT NOT NULL DEFAULT 0,
                exp_balance BIGINT NOT NULL DEFAULT 0,
                version BIGINT NOT NULL DEFAULT 1,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_banks_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS bank_transactions (
                transaction_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                actor_uuid VARCHAR(36) NOT NULL,
                currency_id VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
                currency_scale INT NOT NULL DEFAULT 2,
                delta_amount_minor_units BIGINT NOT NULL,
                resulting_balance_minor_units BIGINT NOT NULL,
                reason VARCHAR(64) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_bank_transactions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_bank_transactions_island_date ON bank_transactions (island_id, created_at DESC);
            CREATE INDEX idx_bank_transactions_operation ON bank_transactions (operation_id);

            CREATE TABLE IF NOT EXISTS processed_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_scope VARCHAR(32) NOT NULL,
                actor_id VARCHAR(36) NOT NULL,
                idempotency_key VARCHAR(64) NOT NULL,
                operation_type VARCHAR(64) NOT NULL,
                resource_id VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                result_code VARCHAR(64) NULL,
                result_payload JSONB NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                completed_at TIMESTAMP NULL,
                CONSTRAINT uq_processed_ops UNIQUE (operation_scope, actor_id, idempotency_key)
            );

            CREATE INDEX idx_processed_operations_resource ON processed_operations (resource_id);
            """;
}
