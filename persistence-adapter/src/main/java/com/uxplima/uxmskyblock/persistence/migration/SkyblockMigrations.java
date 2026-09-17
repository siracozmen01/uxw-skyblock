package com.uxplima.uxmskyblock.persistence.migration;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Production schema migration registry for Skyblock persistence.
 *
 * <p>Maintains the canonical, ordered list of production migrations across supported
 * V1 SQL targets: SQLite, MariaDB (represented by upstream {@link Dialect#MYSQL}),
 * and PostgreSQL ({@link Dialect#POSTGRES}).
 *
 * <p>Execution, state tracking, and transactions are delegated directly to
 * {@link com.uxplima.uxmlib.storage.migration.MigrationRunner} from {@code uxmlib-storage}.
 *
 * <p>Version numbers are monotonically increasing positive integers.
 */
public final class SkyblockMigrations {

    /** The latest production schema version. */
    public static final int LATEST_VERSION = 10;

    /** Human-readable description of migration V1. */
    public static final String V1_DESCRIPTION = "create player accounts profiles and sessions";

    /** Human-readable description of migration V2. */
    public static final String V2_DESCRIPTION = "create profile inventories";

    /** Human-readable description of migration V3. */
    public static final String V3_DESCRIPTION = "create inventory mutation journals";

    /** Human-readable description of migration V4. */
    public static final String V4_DESCRIPTION = "create profile switch operations";

    /** Human-readable description of migration V5. */
    public static final String V5_DESCRIPTION = "create islands and island authority structures";

    /** Human-readable description of migration V6. */
    public static final String V6_DESCRIPTION = "create island banks bank transactions and processed operations";

    /** Human-readable description of migration V7. */
    public static final String V7_DESCRIPTION = "create island upgrades and leaderboard indexes";

    /** Human-readable description of migration V8. */
    public static final String V8_DESCRIPTION = "create backup operations catalog";

    /** Human-readable description of migration V9. */
    public static final String V9_DESCRIPTION = "create outbox events and consumer inbox";

    /** Human-readable description of migration V10. */
    public static final String V10_DESCRIPTION = "create world grid allocations";

    private SkyblockMigrations() {}

    /**
     * Returns the ordered immutable list of production migrations for the specified dialect.
     *
     * <p>Explicitly dispatches DDL for supported V1 dialects: SQLite, MariaDB (upstream
     * {@link Dialect#MYSQL}), and PostgreSQL ({@link Dialect#POSTGRES}). Unsupported dialects
     * (such as {@link Dialect#H2} or {@link Dialect#GENERIC}) fail fast with {@link IllegalArgumentException}.
     *
     * @param dialect the target SQL dialect
     * @return ordered list of production migrations
     * @throws NullPointerException if dialect is null
     * @throws IllegalArgumentException if dialect is unsupported
     */
    public static List<Migration> getMigrations(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        return List.of(
                v1Migration(dialect),
                v2Migration(dialect),
                v3Migration(dialect),
                v4Migration(dialect),
                v5Migration(dialect),
                v6Migration(dialect),
                v7Migration(dialect),
                v8Migration(dialect),
                v9Migration(dialect),
                v10Migration(dialect));
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

            ALTER TABLE player_accounts ADD CONSTRAINT fk_player_accounts_active_profile
                FOREIGN KEY (player_uuid, active_profile_id) REFERENCES player_profiles (player_uuid, profile_id);
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

    private static Migration v10Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(10, V10_DESCRIPTION, SQLITE_V10_DDL);
            case MYSQL -> new Migration(10, V10_DESCRIPTION, MYSQL_V10_DDL);
            case POSTGRES -> new Migration(10, V10_DESCRIPTION, POSTGRES_V10_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

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

    private static final String SQLITE_V10_DDL = """
            CREATE TABLE IF NOT EXISTS world_grid_allocations (
                sequence_index BIGINT NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                center_x INT NOT NULL,
                center_z INT NOT NULL,
                island_id VARCHAR(36) NULL,
                allocated_by_node VARCHAR(64) NOT NULL,
                allocated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_grid_coords ON world_grid_allocations (world_name, center_x, center_z);
            CREATE INDEX IF NOT EXISTS idx_grid_island ON world_grid_allocations (island_id);
            """;

    private static final String MYSQL_V10_DDL = """
            CREATE TABLE IF NOT EXISTS world_grid_allocations (
                sequence_index BIGINT NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                center_x INT NOT NULL,
                center_z INT NOT NULL,
                island_id VARCHAR(36) NULL,
                allocated_by_node VARCHAR(64) NOT NULL,
                allocated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_grid_coords ON world_grid_allocations (world_name, center_x, center_z);
            CREATE INDEX idx_grid_island ON world_grid_allocations (island_id);
            """;

    private static final String POSTGRES_V10_DDL = """
            CREATE TABLE IF NOT EXISTS world_grid_allocations (
                sequence_index BIGINT NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                center_x INT NOT NULL,
                center_z INT NOT NULL,
                island_id VARCHAR(36) NULL,
                allocated_by_node VARCHAR(64) NOT NULL,
                allocated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_grid_coords ON world_grid_allocations (world_name, center_x, center_z);
            CREATE INDEX idx_grid_island ON world_grid_allocations (island_id);
            """;
}
