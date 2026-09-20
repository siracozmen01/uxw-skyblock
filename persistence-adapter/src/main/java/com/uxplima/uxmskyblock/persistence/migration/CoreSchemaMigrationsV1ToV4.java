package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V1_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V2_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V3_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V4_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class CoreSchemaMigrationsV1ToV4 {

    private CoreSchemaMigrationsV1ToV4() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v1Migration(dialect), v2Migration(dialect), v3Migration(dialect), v4Migration(dialect));
    }

    private static Migration v1Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(1, V1_DESCRIPTION, SQLITE_V1_DDL);
            case MYSQL, POSTGRES -> new Migration(1, V1_DESCRIPTION, SERVER_V1_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v2Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(2, V2_DESCRIPTION, SQLITE_V2_DDL);
            case MYSQL -> new Migration(2, V2_DESCRIPTION, MYSQL_V2_DDL);
            case POSTGRES -> new Migration(2, V2_DESCRIPTION, POSTGRES_V2_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v3Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(3, V3_DESCRIPTION, SQLITE_V3_DDL);
            case MYSQL -> new Migration(3, V3_DESCRIPTION, MYSQL_V3_DDL);
            case POSTGRES -> new Migration(3, V3_DESCRIPTION, POSTGRES_V3_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v4Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(4, V4_DESCRIPTION, SQLITE_V4_DDL);
            case MYSQL -> new Migration(4, V4_DESCRIPTION, MYSQL_V4_DDL);
            case POSTGRES -> new Migration(4, V4_DESCRIPTION, POSTGRES_V4_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    // SQLite permits forward-referencing foreign keys at table creation time,
    // but does not support ALTER TABLE ADD CONSTRAINT.
    private static final String SQLITE_V1_DDL = """
            CREATE TABLE IF NOT EXISTS player_accounts (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                active_profile_id VARCHAR(36) NULL,
                active_switch_operation_id VARCHAR(36) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_player_accounts_active_profile FOREIGN KEY (player_uuid, active_profile_id)
                    REFERENCES player_profiles (player_uuid, profile_id)
            );

            CREATE TABLE IF NOT EXISTS player_profiles (
                profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                profile_type VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_player_profiles_account FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE,
                CONSTRAINT uq_player_profiles_ownership UNIQUE (player_uuid, profile_id)
            );

            CREATE INDEX IF NOT EXISTS idx_player_profiles_player ON player_profiles (player_uuid);

            CREATE TABLE IF NOT EXISTS player_sessions (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                active_profile_id VARCHAR(36) NOT NULL,
                authoritative_node VARCHAR(64) NOT NULL,
                session_epoch BIGINT NOT NULL DEFAULT 1,
                state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                handoff_id VARCHAR(36) NULL,
                handoff_target_node VARCHAR(64) NULL,
                handoff_expires_at TIMESTAMP NULL,
                last_durable_inventory_version BIGINT NOT NULL DEFAULT 1,
                lease_expires_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_player_sessions_account FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE,
                CONSTRAINT fk_player_sessions_active_profile FOREIGN KEY (player_uuid, active_profile_id)
                    REFERENCES player_profiles (player_uuid, profile_id)
            );

            CREATE INDEX IF NOT EXISTS idx_player_sessions_state ON player_sessions (state, lease_expires_at);
            """;

    // MariaDB and PostgreSQL require referenced tables to exist before foreign key creation,
    // so the circular foreign key on player_accounts is attached after player_profiles exists.
    private static final String SERVER_V1_DDL = """
            CREATE TABLE IF NOT EXISTS player_accounts (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                active_profile_id VARCHAR(36) NULL,
                active_switch_operation_id VARCHAR(36) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS player_profiles (
                profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                profile_type VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_player_profiles_account FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE,
                CONSTRAINT uq_player_profiles_ownership UNIQUE (player_uuid, profile_id)
            );

            CREATE INDEX idx_player_profiles_player ON player_profiles (player_uuid);

            CREATE TABLE IF NOT EXISTS player_sessions (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                active_profile_id VARCHAR(36) NOT NULL,
                authoritative_node VARCHAR(64) NOT NULL,
                session_epoch BIGINT NOT NULL DEFAULT 1,
                state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                handoff_id VARCHAR(36) NULL,
                handoff_target_node VARCHAR(64) NULL,
                handoff_expires_at TIMESTAMP NULL,
                last_durable_inventory_version BIGINT NOT NULL DEFAULT 1,
                lease_expires_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_player_sessions_account FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE,
                CONSTRAINT fk_player_sessions_active_profile FOREIGN KEY (player_uuid, active_profile_id)
                    REFERENCES player_profiles (player_uuid, profile_id)
            );

            CREATE INDEX idx_player_sessions_state ON player_sessions (state, lease_expires_at);

            ALTER TABLE player_accounts
                ADD CONSTRAINT fk_player_accounts_active_profile
                FOREIGN KEY (player_uuid, active_profile_id)
                REFERENCES player_profiles (player_uuid, profile_id);
            """;

    private static final String SQLITE_V2_DDL = """
            CREATE TABLE IF NOT EXISTS profile_inventories (
                profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                profile_inventory_version BIGINT NOT NULL DEFAULT 1,
                inventory_nbt BLOB NOT NULL,
                enderchest_nbt BLOB NOT NULL,
                experience_points INT NOT NULL DEFAULT 0,
                health DOUBLE NOT NULL DEFAULT 20.0,
                food_level INT NOT NULL DEFAULT 20,
                saturation FLOAT NOT NULL DEFAULT 5.0,
                active_potion_effects_nbt BLOB NULL,
                logout_world VARCHAR(64) NULL,
                logout_x DOUBLE NULL,
                logout_y DOUBLE NULL,
                logout_z DOUBLE NULL,
                gamemode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL',
                flight_allowed BOOLEAN NOT NULL DEFAULT FALSE,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_profile_inventories_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );
            """;

    private static final String MYSQL_V2_DDL = """
            CREATE TABLE IF NOT EXISTS profile_inventories (
                profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                profile_inventory_version BIGINT NOT NULL DEFAULT 1,
                inventory_nbt MEDIUMBLOB NOT NULL,
                enderchest_nbt MEDIUMBLOB NOT NULL,
                experience_points INT NOT NULL DEFAULT 0,
                health DOUBLE NOT NULL DEFAULT 20.0,
                food_level INT NOT NULL DEFAULT 20,
                saturation FLOAT NOT NULL DEFAULT 5.0,
                active_potion_effects_nbt MEDIUMBLOB NULL,
                logout_world VARCHAR(64) NULL,
                logout_x DOUBLE NULL,
                logout_y DOUBLE NULL,
                logout_z DOUBLE NULL,
                gamemode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL',
                flight_allowed BOOLEAN NOT NULL DEFAULT FALSE,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_profile_inventories_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );
            """;

    private static final String POSTGRES_V2_DDL = """
            CREATE TABLE IF NOT EXISTS profile_inventories (
                profile_id VARCHAR(36) NOT NULL PRIMARY KEY,
                profile_inventory_version BIGINT NOT NULL DEFAULT 1,
                inventory_nbt BYTEA NOT NULL,
                enderchest_nbt BYTEA NOT NULL,
                experience_points INT NOT NULL DEFAULT 0,
                health DOUBLE PRECISION NOT NULL DEFAULT 20.0,
                food_level INT NOT NULL DEFAULT 20,
                saturation REAL NOT NULL DEFAULT 5.0,
                active_potion_effects_nbt BYTEA NULL,
                logout_world VARCHAR(64) NULL,
                logout_x DOUBLE PRECISION NULL,
                logout_y DOUBLE PRECISION NULL,
                logout_z DOUBLE PRECISION NULL,
                gamemode VARCHAR(16) NOT NULL DEFAULT 'SURVIVAL',
                flight_allowed BOOLEAN NOT NULL DEFAULT FALSE,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_profile_inventories_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );
            """;

    private static final String SQLITE_V3_DDL = """
            CREATE TABLE inventory_mutation_journals (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_type VARCHAR(64) NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'INTENT',
                participant_count INT NOT NULL DEFAULT 1,
                payload TEXT NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_inv_journal_state ON inventory_mutation_journals (state, expires_at);

            CREATE TABLE inventory_mutation_participants (
                operation_id VARCHAR(36) NOT NULL,
                participant_index INT NOT NULL,
                inventory_type VARCHAR(32) NOT NULL,
                owner_root_type VARCHAR(32) NOT NULL,
                owner_root_id VARCHAR(64) NOT NULL,
                expected_version BIGINT NOT NULL,
                authority_type VARCHAR(32) NOT NULL,
                authority_id VARCHAR(64) NOT NULL,
                authority_epoch BIGINT NOT NULL,
                before_fingerprint VARCHAR(64) NOT NULL,
                after_fingerprint VARCHAR(64) NOT NULL,
                durable_apply_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                mutation_delta_payload TEXT NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (operation_id, participant_index),
                CONSTRAINT fk_inv_participant_journal FOREIGN KEY (operation_id)
                    REFERENCES inventory_mutation_journals (operation_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_inv_participant_lookup ON inventory_mutation_participants (owner_root_type, owner_root_id, durable_apply_state);
            """;

    private static final String MYSQL_V3_DDL = """
            CREATE TABLE inventory_mutation_journals (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_type VARCHAR(64) NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'INTENT',
                participant_count INT NOT NULL DEFAULT 1,
                payload JSON NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_inv_journal_state ON inventory_mutation_journals (state, expires_at);

            CREATE TABLE inventory_mutation_participants (
                operation_id VARCHAR(36) NOT NULL,
                participant_index INT NOT NULL,
                inventory_type VARCHAR(32) NOT NULL,
                owner_root_type VARCHAR(32) NOT NULL,
                owner_root_id VARCHAR(64) NOT NULL,
                expected_version BIGINT NOT NULL,
                authority_type VARCHAR(32) NOT NULL,
                authority_id VARCHAR(64) NOT NULL,
                authority_epoch BIGINT NOT NULL,
                before_fingerprint VARCHAR(64) NOT NULL,
                after_fingerprint VARCHAR(64) NOT NULL,
                durable_apply_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                mutation_delta_payload JSON NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (operation_id, participant_index),
                CONSTRAINT fk_inv_participant_journal FOREIGN KEY (operation_id)
                    REFERENCES inventory_mutation_journals (operation_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_inv_participant_lookup ON inventory_mutation_participants (owner_root_type, owner_root_id, durable_apply_state);
            """;

    private static final String POSTGRES_V3_DDL = """
            CREATE TABLE inventory_mutation_journals (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                operation_type VARCHAR(64) NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'INTENT',
                participant_count INT NOT NULL DEFAULT 1,
                payload JSON NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_inv_journal_state ON inventory_mutation_journals (state, expires_at);

            CREATE TABLE inventory_mutation_participants (
                operation_id VARCHAR(36) NOT NULL,
                participant_index INT NOT NULL,
                inventory_type VARCHAR(32) NOT NULL,
                owner_root_type VARCHAR(32) NOT NULL,
                owner_root_id VARCHAR(64) NOT NULL,
                expected_version BIGINT NOT NULL,
                authority_type VARCHAR(32) NOT NULL,
                authority_id VARCHAR(64) NOT NULL,
                authority_epoch BIGINT NOT NULL,
                before_fingerprint VARCHAR(64) NOT NULL,
                after_fingerprint VARCHAR(64) NOT NULL,
                durable_apply_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                mutation_delta_payload JSON NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (operation_id, participant_index),
                CONSTRAINT fk_inv_participant_journal FOREIGN KEY (operation_id)
                    REFERENCES inventory_mutation_journals (operation_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_inv_participant_lookup ON inventory_mutation_participants (owner_root_type, owner_root_id, durable_apply_state);
            """;

    private static final String SQLITE_V4_DDL = """
            CREATE TABLE profile_switch_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                from_profile_id VARCHAR(36) NOT NULL,
                to_profile_id VARCHAR(36) NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
                source_snapshot_blob BLOB NULL,
                target_snapshot_blob BLOB NULL,
                failure_reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_profile_switch_player FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE
            );

            CREATE INDEX idx_profile_switch_player ON profile_switch_operations (player_uuid, state);
            """;

    private static final String MYSQL_V4_DDL = """
            CREATE TABLE profile_switch_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                from_profile_id VARCHAR(36) NOT NULL,
                to_profile_id VARCHAR(36) NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
                source_snapshot_blob LONGBLOB NULL,
                target_snapshot_blob LONGBLOB NULL,
                failure_reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_profile_switch_player FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE
            );

            CREATE INDEX idx_profile_switch_player ON profile_switch_operations (player_uuid, state);
            """;

    private static final String POSTGRES_V4_DDL = """
            CREATE TABLE profile_switch_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                from_profile_id VARCHAR(36) NOT NULL,
                to_profile_id VARCHAR(36) NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
                source_snapshot_blob BYTEA NULL,
                target_snapshot_blob BYTEA NULL,
                failure_reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_profile_switch_player FOREIGN KEY (player_uuid)
                    REFERENCES player_accounts (player_uuid) ON DELETE CASCADE
            );

            CREATE INDEX idx_profile_switch_player ON profile_switch_operations (player_uuid, state);
            """;
}
