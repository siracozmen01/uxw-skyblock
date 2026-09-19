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
    public static final int LATEST_VERSION = 22;

    /** Human-readable description of migration V22. */
    public static final String V22_DESCRIPTION = "create player anti abuse records and island quarantines";

    /** Human-readable description of migration V21. */
    public static final String V21_DESCRIPTION = "create spiral slot pool for coordinate recycling";

    /** Human-readable description of migration V20. */
    public static final String V20_DESCRIPTION = "create island missions and quest progress";

    /** Human-readable description of migration V19. */
    public static final String V19_DESCRIPTION =
            "create island vault pages edit sessions escrow transfers and audit logs";

    /** Human-readable description of migration V18. */
    public static final String V18_DESCRIPTION = "create island warps and island bans";

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

    /** Human-readable description of migration V11. */
    public static final String V11_DESCRIPTION = "create economy sagas";

    /** Human-readable description of migration V12. */
    public static final String V12_DESCRIPTION = "create island creation unique constraints";

    /** Human-readable description of migration V13. */
    public static final String V13_DESCRIPTION = "create island seasons snapshots and payouts";

    /** Human-readable description of migration V14. */
    public static final String V14_DESCRIPTION = "create social ratings guestbook subject visits and bookmarks";

    /** Human-readable description of migration V15. */
    public static final String V15_DESCRIPTION = "create island alliances and invites";

    /** Human-readable description of migration V16. */
    public static final String V16_DESCRIPTION = "create temporary access grants and permissions";

    /** Human-readable description of migration V17. */
    public static final String V17_DESCRIPTION = "create reward grants and components";

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
                v10Migration(dialect),
                v11Migration(dialect),
                v12Migration(dialect),
                v13Migration(dialect),
                v14Migration(dialect),
                v15Migration(dialect),
                v16Migration(dialect),
                v17Migration(dialect),
                v18Migration(dialect),
                v19Migration(dialect),
                v20Migration(dialect),
                v21Migration(dialect),
                v22Migration(dialect));
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

    private static Migration v11Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(11, V11_DESCRIPTION, SQLITE_V11_DDL);
            case MYSQL -> new Migration(11, V11_DESCRIPTION, MYSQL_V11_DDL);
            case POSTGRES -> new Migration(11, V11_DESCRIPTION, POSTGRES_V11_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v12Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(12, V12_DESCRIPTION, SQLITE_V12_DDL);
            case MYSQL -> new Migration(12, V12_DESCRIPTION, MYSQL_V12_DDL);
            case POSTGRES -> new Migration(12, V12_DESCRIPTION, POSTGRES_V12_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v13Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(13, V13_DESCRIPTION, SQLITE_V13_DDL);
            case MYSQL -> new Migration(13, V13_DESCRIPTION, MYSQL_V13_DDL);
            case POSTGRES -> new Migration(13, V13_DESCRIPTION, POSTGRES_V13_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v14Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(14, V14_DESCRIPTION, SQLITE_V14_DDL);
            case MYSQL -> new Migration(14, V14_DESCRIPTION, MYSQL_V14_DDL);
            case POSTGRES -> new Migration(14, V14_DESCRIPTION, POSTGRES_V14_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v15Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(15, V15_DESCRIPTION, SQLITE_V15_DDL);
            case MYSQL -> new Migration(15, V15_DESCRIPTION, MYSQL_V15_DDL);
            case POSTGRES -> new Migration(15, V15_DESCRIPTION, POSTGRES_V15_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v16Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(16, V16_DESCRIPTION, SQLITE_V16_DDL);
            case MYSQL -> new Migration(16, V16_DESCRIPTION, MYSQL_V16_DDL);
            case POSTGRES -> new Migration(16, V16_DESCRIPTION, POSTGRES_V16_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v17Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(17, V17_DESCRIPTION, SQLITE_V17_DDL);
            case MYSQL -> new Migration(17, V17_DESCRIPTION, MYSQL_V17_DDL);
            case POSTGRES -> new Migration(17, V17_DESCRIPTION, POSTGRES_V17_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v18Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(18, V18_DESCRIPTION, SQLITE_V18_DDL);
            case MYSQL -> new Migration(18, V18_DESCRIPTION, MYSQL_V18_DDL);
            case POSTGRES -> new Migration(18, V18_DESCRIPTION, POSTGRES_V18_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v19Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(19, V19_DESCRIPTION, SQLITE_V19_DDL);
            case MYSQL -> new Migration(19, V19_DESCRIPTION, MYSQL_V19_DDL);
            case POSTGRES -> new Migration(19, V19_DESCRIPTION, POSTGRES_V19_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v20Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(20, V20_DESCRIPTION, SQLITE_V20_DDL);
            case MYSQL -> new Migration(20, V20_DESCRIPTION, MYSQL_V20_DDL);
            case POSTGRES -> new Migration(20, V20_DESCRIPTION, POSTGRES_V20_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v21Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(21, V21_DESCRIPTION, SQLITE_V21_DDL);
            case MYSQL -> new Migration(21, V21_DESCRIPTION, MYSQL_V21_DDL);
            case POSTGRES -> new Migration(21, V21_DESCRIPTION, POSTGRES_V21_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v22Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(22, V22_DESCRIPTION, SQLITE_V22_DDL);
            case MYSQL -> new Migration(22, V22_DESCRIPTION, MYSQL_V22_DDL);
            case POSTGRES -> new Migration(22, V22_DESCRIPTION, POSTGRES_V22_DDL);
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

    private static final String SQLITE_V11_DDL = """
            CREATE TABLE IF NOT EXISTS economy_sagas (
                saga_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                saga_type VARCHAR(32) NOT NULL,
                state VARCHAR(32) NOT NULL,
                amount_minor_units BIGINT NOT NULL,
                currency VARCHAR(32) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_economy_sagas_state ON economy_sagas (state, expires_at);
            CREATE INDEX IF NOT EXISTS idx_economy_sagas_player ON economy_sagas (player_uuid, profile_id);
            CREATE INDEX IF NOT EXISTS idx_economy_sagas_island ON economy_sagas (island_id);
            """;

    private static final String MYSQL_V11_DDL = """
            CREATE TABLE IF NOT EXISTS economy_sagas (
                saga_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                saga_type VARCHAR(32) NOT NULL,
                state VARCHAR(32) NOT NULL,
                amount_minor_units BIGINT NOT NULL,
                currency VARCHAR(32) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_economy_sagas_state ON economy_sagas (state, expires_at);
            CREATE INDEX idx_economy_sagas_player ON economy_sagas (player_uuid, profile_id);
            CREATE INDEX idx_economy_sagas_island ON economy_sagas (island_id);
            """;

    private static final String POSTGRES_V11_DDL = """
            CREATE TABLE IF NOT EXISTS economy_sagas (
                saga_id VARCHAR(36) NOT NULL PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                saga_type VARCHAR(32) NOT NULL,
                state VARCHAR(32) NOT NULL,
                amount_minor_units BIGINT NOT NULL,
                currency VARCHAR(32) NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_economy_sagas_state ON economy_sagas (state, expires_at);
            CREATE INDEX idx_economy_sagas_player ON economy_sagas (player_uuid, profile_id);
            CREATE INDEX idx_economy_sagas_island ON economy_sagas (island_id);
            """;

    private static final String SQLITE_V12_DDL = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_island_locations_coords ON island_locations (world_name, center_x, center_z);
            CREATE UNIQUE INDEX IF NOT EXISTS uq_islands_owner_profile ON islands (owner_profile_id);
            CREATE UNIQUE INDEX IF NOT EXISTS uq_grid_allocations_coords ON world_grid_allocations (world_name, center_x, center_z);
            """;

    private static final String MYSQL_V12_DDL = """
            CREATE UNIQUE INDEX uq_island_locations_coords ON island_locations (world_name, center_x, center_z);
            CREATE UNIQUE INDEX uq_islands_owner_profile ON islands (owner_profile_id);
            CREATE UNIQUE INDEX uq_grid_allocations_coords ON world_grid_allocations (world_name, center_x, center_z);
            """;

    private static final String POSTGRES_V12_DDL = """
            CREATE UNIQUE INDEX IF NOT EXISTS uq_island_locations_coords ON island_locations (world_name, center_x, center_z);
            CREATE UNIQUE INDEX IF NOT EXISTS uq_islands_owner_profile ON islands (owner_profile_id);
            CREATE UNIQUE INDEX IF NOT EXISTS uq_grid_allocations_coords ON world_grid_allocations (world_name, center_x, center_z);
            """;

    private static final String SQLITE_V13_DDL = """
            CREATE TABLE IF NOT EXISTS island_seasons (
                season_id INT NOT NULL PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                starts_at TIMESTAMP NOT NULL,
                ends_at TIMESTAMP NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_island_seasons_state ON island_seasons (state, starts_at, ends_at);

            CREATE TABLE IF NOT EXISTS season_snapshots (
                season_id INT NOT NULL,
                metric VARCHAR(32) NOT NULL,
                rank INT NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                owner_player_uuid VARCHAR(36) NOT NULL,
                score BIGINT NOT NULL,
                snapshot_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (season_id, metric, rank),
                CONSTRAINT fk_season_snapshots_season FOREIGN KEY (season_id)
                    REFERENCES island_seasons (season_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_season_snapshots_metric ON season_snapshots (season_id, metric, rank);
            CREATE INDEX IF NOT EXISTS idx_season_snapshots_island ON season_snapshots (island_id);
            CREATE INDEX IF NOT EXISTS idx_season_snapshots_owner ON season_snapshots (owner_player_uuid);

            CREATE TABLE IF NOT EXISTS season_payouts (
                payout_id VARCHAR(36) NOT NULL PRIMARY KEY,
                season_id INT NOT NULL,
                recipient_uuid VARCHAR(36) NOT NULL,
                reward_action VARCHAR(255) NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                dispatched_at TIMESTAMP NULL,
                CONSTRAINT fk_season_payouts_season FOREIGN KEY (season_id)
                    REFERENCES island_seasons (season_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_season_payouts_recipient ON season_payouts (recipient_uuid, state);
            CREATE INDEX IF NOT EXISTS idx_season_payouts_season ON season_payouts (season_id);
            """;

    private static final String MYSQL_V13_DDL = """
            CREATE TABLE IF NOT EXISTS island_seasons (
                season_id INT NOT NULL PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                starts_at TIMESTAMP NOT NULL,
                ends_at TIMESTAMP NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_island_seasons_state ON island_seasons (state, starts_at, ends_at);

            CREATE TABLE IF NOT EXISTS season_snapshots (
                season_id INT NOT NULL,
                metric VARCHAR(32) NOT NULL,
                rank INT NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                owner_player_uuid VARCHAR(36) NOT NULL,
                score BIGINT NOT NULL,
                snapshot_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (season_id, metric, rank),
                CONSTRAINT fk_season_snapshots_season FOREIGN KEY (season_id)
                    REFERENCES island_seasons (season_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_season_snapshots_metric ON season_snapshots (season_id, metric, rank);
            CREATE INDEX idx_season_snapshots_island ON season_snapshots (island_id);
            CREATE INDEX idx_season_snapshots_owner ON season_snapshots (owner_player_uuid);

            CREATE TABLE IF NOT EXISTS season_payouts (
                payout_id VARCHAR(36) NOT NULL PRIMARY KEY,
                season_id INT NOT NULL,
                recipient_uuid VARCHAR(36) NOT NULL,
                reward_action VARCHAR(255) NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                dispatched_at TIMESTAMP NULL,
                CONSTRAINT fk_season_payouts_season FOREIGN KEY (season_id)
                    REFERENCES island_seasons (season_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_season_payouts_recipient ON season_payouts (recipient_uuid, state);
            CREATE INDEX idx_season_payouts_season ON season_payouts (season_id);
            """;

    private static final String POSTGRES_V13_DDL = """
            CREATE TABLE IF NOT EXISTS island_seasons (
                season_id INT NOT NULL PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                starts_at TIMESTAMP NOT NULL,
                ends_at TIMESTAMP NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_island_seasons_state ON island_seasons (state, starts_at, ends_at);

            CREATE TABLE IF NOT EXISTS season_snapshots (
                season_id INT NOT NULL,
                metric VARCHAR(32) NOT NULL,
                rank INT NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                owner_player_uuid VARCHAR(36) NOT NULL,
                score BIGINT NOT NULL,
                snapshot_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (season_id, metric, rank),
                CONSTRAINT fk_season_snapshots_season FOREIGN KEY (season_id)
                    REFERENCES island_seasons (season_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_season_snapshots_metric ON season_snapshots (season_id, metric, rank);
            CREATE INDEX idx_season_snapshots_island ON season_snapshots (island_id);
            CREATE INDEX idx_season_snapshots_owner ON season_snapshots (owner_player_uuid);

            CREATE TABLE IF NOT EXISTS season_payouts (
                payout_id VARCHAR(36) NOT NULL PRIMARY KEY,
                season_id INT NOT NULL,
                recipient_uuid VARCHAR(36) NOT NULL,
                reward_action VARCHAR(255) NOT NULL,
                state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                dispatched_at TIMESTAMP NULL,
                CONSTRAINT fk_season_payouts_season FOREIGN KEY (season_id)
                    REFERENCES island_seasons (season_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_season_payouts_recipient ON season_payouts (recipient_uuid, state);
            CREATE INDEX idx_season_payouts_season ON season_payouts (season_id);
            """;

    private static final String SQLITE_V14_DDL = """
            CREATE TABLE IF NOT EXISTS social_ratings (
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                rater_profile_id VARCHAR(36) NOT NULL,
                score INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (subject_type_id, subject_key, rater_profile_id)
            );

            CREATE INDEX IF NOT EXISTS idx_social_ratings_subject ON social_ratings (subject_type_id, subject_key);
            CREATE INDEX IF NOT EXISTS idx_social_ratings_rater ON social_ratings (rater_profile_id);

            CREATE TABLE IF NOT EXISTS guestbook_reviews (
                review_id VARCHAR(36) NOT NULL PRIMARY KEY,
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                author_profile_id VARCHAR(36) NOT NULL,
                message VARCHAR(512) NOT NULL,
                is_hidden BOOLEAN NOT NULL DEFAULT 0,
                is_pinned BOOLEAN NOT NULL DEFAULT 0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_guestbook_subject ON guestbook_reviews (subject_type_id, subject_key);
            CREATE INDEX IF NOT EXISTS idx_guestbook_author ON guestbook_reviews (author_profile_id);

            CREATE TABLE IF NOT EXISTS subject_visits (
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                visitor_profile_id VARCHAR(36) NOT NULL,
                visit_count INT NOT NULL DEFAULT 1,
                first_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (subject_type_id, subject_key, visitor_profile_id)
            );

            CREATE INDEX IF NOT EXISTS idx_subject_visits_visitor ON subject_visits (visitor_profile_id);

            CREATE TABLE IF NOT EXISTS social_bookmarks (
                profile_id VARCHAR(36) NOT NULL,
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (profile_id, subject_type_id, subject_key)
            );

            CREATE INDEX IF NOT EXISTS idx_social_bookmarks_subject ON social_bookmarks (subject_type_id, subject_key);
            """;

    private static final String MYSQL_V14_DDL = """
            CREATE TABLE IF NOT EXISTS social_ratings (
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                rater_profile_id VARCHAR(36) NOT NULL,
                score INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (subject_type_id, subject_key, rater_profile_id)
            );

            CREATE INDEX idx_social_ratings_subject ON social_ratings (subject_type_id, subject_key);
            CREATE INDEX idx_social_ratings_rater ON social_ratings (rater_profile_id);

            CREATE TABLE IF NOT EXISTS guestbook_reviews (
                review_id VARCHAR(36) NOT NULL PRIMARY KEY,
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                author_profile_id VARCHAR(36) NOT NULL,
                message VARCHAR(512) NOT NULL,
                is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
                is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_guestbook_subject ON guestbook_reviews (subject_type_id, subject_key);
            CREATE INDEX idx_guestbook_author ON guestbook_reviews (author_profile_id);

            CREATE TABLE IF NOT EXISTS subject_visits (
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                visitor_profile_id VARCHAR(36) NOT NULL,
                visit_count INT NOT NULL DEFAULT 1,
                first_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (subject_type_id, subject_key, visitor_profile_id)
            );

            CREATE INDEX idx_subject_visits_visitor ON subject_visits (visitor_profile_id);

            CREATE TABLE IF NOT EXISTS social_bookmarks (
                profile_id VARCHAR(36) NOT NULL,
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (profile_id, subject_type_id, subject_key)
            );

            CREATE INDEX idx_social_bookmarks_subject ON social_bookmarks (subject_type_id, subject_key);
            """;

    private static final String POSTGRES_V14_DDL = """
            CREATE TABLE IF NOT EXISTS social_ratings (
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                rater_profile_id VARCHAR(36) NOT NULL,
                score INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (subject_type_id, subject_key, rater_profile_id)
            );

            CREATE INDEX idx_social_ratings_subject ON social_ratings (subject_type_id, subject_key);
            CREATE INDEX idx_social_ratings_rater ON social_ratings (rater_profile_id);

            CREATE TABLE IF NOT EXISTS guestbook_reviews (
                review_id VARCHAR(36) NOT NULL PRIMARY KEY,
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                author_profile_id VARCHAR(36) NOT NULL,
                message VARCHAR(512) NOT NULL,
                is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
                is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_guestbook_subject ON guestbook_reviews (subject_type_id, subject_key);
            CREATE INDEX idx_guestbook_author ON guestbook_reviews (author_profile_id);

            CREATE TABLE IF NOT EXISTS subject_visits (
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                visitor_profile_id VARCHAR(36) NOT NULL,
                visit_count INT NOT NULL DEFAULT 1,
                first_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                last_visited_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (subject_type_id, subject_key, visitor_profile_id)
            );

            CREATE INDEX idx_subject_visits_visitor ON subject_visits (visitor_profile_id);

            CREATE TABLE IF NOT EXISTS social_bookmarks (
                profile_id VARCHAR(36) NOT NULL,
                subject_type_id VARCHAR(64) NOT NULL,
                subject_key VARCHAR(128) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (profile_id, subject_type_id, subject_key)
            );

            CREATE INDEX idx_social_bookmarks_subject ON social_bookmarks (subject_type_id, subject_key);
            """;

    private static final String SQLITE_V15_DDL = """
            CREATE TABLE IF NOT EXISTS island_alliances (
                alliance_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_a_id VARCHAR(36) NOT NULL,
                island_b_id VARCHAR(36) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_island_alliances_pair UNIQUE (island_a_id, island_b_id)
            );

            CREATE INDEX IF NOT EXISTS idx_island_alliances_a ON island_alliances (island_a_id);
            CREATE INDEX IF NOT EXISTS idx_island_alliances_b ON island_alliances (island_b_id);

            CREATE TABLE IF NOT EXISTS island_alliance_invites (
                invite_id VARCHAR(36) NOT NULL PRIMARY KEY,
                sender_island_id VARCHAR(36) NOT NULL,
                target_island_id VARCHAR(36) NOT NULL,
                sender_profile_id VARCHAR(36) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                CONSTRAINT uq_alliance_invites_pair UNIQUE (sender_island_id, target_island_id)
            );

            CREATE INDEX IF NOT EXISTS idx_alliance_invites_target ON island_alliance_invites (target_island_id);
            CREATE INDEX IF NOT EXISTS idx_alliance_invites_sender ON island_alliance_invites (sender_island_id);
            """;

    private static final String MYSQL_V15_DDL = """
            CREATE TABLE IF NOT EXISTS island_alliances (
                alliance_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_a_id VARCHAR(36) NOT NULL,
                island_b_id VARCHAR(36) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_island_alliances_pair UNIQUE (island_a_id, island_b_id)
            );

            CREATE INDEX idx_island_alliances_a ON island_alliances (island_a_id);
            CREATE INDEX idx_island_alliances_b ON island_alliances (island_b_id);

            CREATE TABLE IF NOT EXISTS island_alliance_invites (
                invite_id VARCHAR(36) NOT NULL PRIMARY KEY,
                sender_island_id VARCHAR(36) NOT NULL,
                target_island_id VARCHAR(36) NOT NULL,
                sender_profile_id VARCHAR(36) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                CONSTRAINT uq_alliance_invites_pair UNIQUE (sender_island_id, target_island_id)
            );

            CREATE INDEX idx_alliance_invites_target ON island_alliance_invites (target_island_id);
            CREATE INDEX idx_alliance_invites_sender ON island_alliance_invites (sender_island_id);
            """;

    private static final String POSTGRES_V15_DDL = """
            CREATE TABLE IF NOT EXISTS island_alliances (
                alliance_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_a_id VARCHAR(36) NOT NULL,
                island_b_id VARCHAR(36) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT uq_island_alliances_pair UNIQUE (island_a_id, island_b_id)
            );

            CREATE INDEX idx_island_alliances_a ON island_alliances (island_a_id);
            CREATE INDEX idx_island_alliances_b ON island_alliances (island_b_id);

            CREATE TABLE IF NOT EXISTS island_alliance_invites (
                invite_id VARCHAR(36) NOT NULL PRIMARY KEY,
                sender_island_id VARCHAR(36) NOT NULL,
                target_island_id VARCHAR(36) NOT NULL,
                sender_profile_id VARCHAR(36) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                CONSTRAINT uq_alliance_invites_pair UNIQUE (sender_island_id, target_island_id)
            );

            CREATE INDEX idx_alliance_invites_target ON island_alliance_invites (target_island_id);
            CREATE INDEX idx_alliance_invites_sender ON island_alliance_invites (sender_island_id);
            """;

    private static final String SQLITE_V16_DDL = """
            CREATE TABLE IF NOT EXISTS temporary_access_grants (
                grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
                instance_id VARCHAR(36) NOT NULL,
                target_root_type_id VARCHAR(64) NOT NULL,
                target_root_key VARCHAR(128) NOT NULL,
                grantee_profile_id VARCHAR(36) NOT NULL,
                granted_by_profile_id VARCHAR(36) NOT NULL,
                termination_policy VARCHAR(32) NOT NULL,
                anchor_player_uuid VARCHAR(36) NULL,
                anchor_session_epoch BIGINT NULL,
                anchor_node_id VARCHAR(64) NULL,
                anchor_process_generation_id VARCHAR(64) NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_temp_grants_grantee ON temporary_access_grants (grantee_profile_id, state);
            CREATE INDEX IF NOT EXISTS idx_temp_grants_instance ON temporary_access_grants (instance_id, state);
            CREATE INDEX IF NOT EXISTS idx_temp_grants_target ON temporary_access_grants (target_root_type_id, target_root_key, state);

            CREATE TABLE IF NOT EXISTS temporary_access_grant_permissions (
                grant_id VARCHAR(36) NOT NULL,
                permission_key VARCHAR(128) NOT NULL,
                PRIMARY KEY (grant_id, permission_key),
                CONSTRAINT fk_temp_grant_perms FOREIGN KEY (grant_id)
                    REFERENCES temporary_access_grants (grant_id) ON DELETE CASCADE
            );
            """;

    private static final String MYSQL_V16_DDL = """
            CREATE TABLE IF NOT EXISTS temporary_access_grants (
                grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
                instance_id VARCHAR(36) NOT NULL,
                target_root_type_id VARCHAR(64) NOT NULL,
                target_root_key VARCHAR(128) NOT NULL,
                grantee_profile_id VARCHAR(36) NOT NULL,
                granted_by_profile_id VARCHAR(36) NOT NULL,
                termination_policy VARCHAR(32) NOT NULL,
                anchor_player_uuid VARCHAR(36) NULL,
                anchor_session_epoch BIGINT NULL,
                anchor_node_id VARCHAR(64) NULL,
                anchor_process_generation_id VARCHAR(64) NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_temp_grants_grantee ON temporary_access_grants (grantee_profile_id, state);
            CREATE INDEX idx_temp_grants_instance ON temporary_access_grants (instance_id, state);
            CREATE INDEX idx_temp_grants_target ON temporary_access_grants (target_root_type_id, target_root_key, state);

            CREATE TABLE IF NOT EXISTS temporary_access_grant_permissions (
                grant_id VARCHAR(36) NOT NULL,
                permission_key VARCHAR(128) NOT NULL,
                PRIMARY KEY (grant_id, permission_key),
                CONSTRAINT fk_temp_grant_perms FOREIGN KEY (grant_id)
                    REFERENCES temporary_access_grants (grant_id) ON DELETE CASCADE
            );
            """;

    private static final String POSTGRES_V16_DDL = """
            CREATE TABLE IF NOT EXISTS temporary_access_grants (
                grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
                instance_id VARCHAR(36) NOT NULL,
                target_root_type_id VARCHAR(64) NOT NULL,
                target_root_key VARCHAR(128) NOT NULL,
                grantee_profile_id VARCHAR(36) NOT NULL,
                granted_by_profile_id VARCHAR(36) NOT NULL,
                termination_policy VARCHAR(32) NOT NULL,
                anchor_player_uuid VARCHAR(36) NULL,
                anchor_session_epoch BIGINT NULL,
                anchor_node_id VARCHAR(64) NULL,
                anchor_process_generation_id VARCHAR(64) NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX idx_temp_grants_grantee ON temporary_access_grants (grantee_profile_id, state);
            CREATE INDEX idx_temp_grants_instance ON temporary_access_grants (instance_id, state);
            CREATE INDEX idx_temp_grants_target ON temporary_access_grants (target_root_type_id, target_root_key, state);

            CREATE TABLE IF NOT EXISTS temporary_access_grant_permissions (
                grant_id VARCHAR(36) NOT NULL,
                permission_key VARCHAR(128) NOT NULL,
                PRIMARY KEY (grant_id, permission_key),
                CONSTRAINT fk_temp_grant_perms FOREIGN KEY (grant_id)
                    REFERENCES temporary_access_grants (grant_id) ON DELETE CASCADE
            );
            """;

    private static final String SQLITE_V17_DDL = """
            CREATE TABLE IF NOT EXISTS reward_grants (
                grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
                recipient_profile_id VARCHAR(36) NOT NULL,
                source_type VARCHAR(64) NOT NULL,
                source_id VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                claimed_at TIMESTAMP NULL,
                expires_at TIMESTAMP NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_reward_grant_recipient FOREIGN KEY (recipient_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_reward_grants_recipient ON reward_grants (recipient_profile_id, state);

            CREATE TABLE IF NOT EXISTS reward_grant_components (
                component_id VARCHAR(36) NOT NULL PRIMARY KEY,
                grant_id VARCHAR(36) NOT NULL,
                component_index INT NOT NULL,
                component_operation_id VARCHAR(36) NOT NULL,
                component_type VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                journal_operation_id VARCHAR(36) NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_reward_comp_grant FOREIGN KEY (grant_id)
                    REFERENCES reward_grants (grant_id) ON DELETE CASCADE,
                CONSTRAINT uq_reward_grant_comp_idx UNIQUE (grant_id, component_index),
                CONSTRAINT uq_reward_grant_comp_op UNIQUE (component_operation_id)
            );

            CREATE INDEX IF NOT EXISTS idx_reward_comp_grant ON reward_grant_components (grant_id, state);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_reward_comp_op ON reward_grant_components (component_operation_id);
            """;

    private static final String MYSQL_V17_DDL = """
            CREATE TABLE IF NOT EXISTS reward_grants (
                grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
                recipient_profile_id VARCHAR(36) NOT NULL,
                source_type VARCHAR(64) NOT NULL,
                source_id VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                claimed_at TIMESTAMP NULL,
                expires_at TIMESTAMP NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_reward_grant_recipient FOREIGN KEY (recipient_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_reward_grants_recipient ON reward_grants (recipient_profile_id, state);

            CREATE TABLE IF NOT EXISTS reward_grant_components (
                component_id VARCHAR(36) NOT NULL PRIMARY KEY,
                grant_id VARCHAR(36) NOT NULL,
                component_index INT NOT NULL,
                component_operation_id VARCHAR(36) NOT NULL,
                component_type VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                journal_operation_id VARCHAR(36) NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_reward_comp_grant FOREIGN KEY (grant_id)
                    REFERENCES reward_grants (grant_id) ON DELETE CASCADE,
                CONSTRAINT uq_reward_grant_comp_idx UNIQUE (grant_id, component_index),
                CONSTRAINT uq_reward_grant_comp_op UNIQUE (component_operation_id)
            );

            CREATE INDEX idx_reward_comp_grant ON reward_grant_components (grant_id, state);
            """;

    private static final String POSTGRES_V17_DDL = """
            CREATE TABLE IF NOT EXISTS reward_grants (
                grant_id VARCHAR(36) NOT NULL PRIMARY KEY,
                recipient_profile_id VARCHAR(36) NOT NULL,
                source_type VARCHAR(64) NOT NULL,
                source_id VARCHAR(64) NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                claimed_at TIMESTAMP NULL,
                expires_at TIMESTAMP NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_reward_grant_recipient FOREIGN KEY (recipient_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_reward_grants_recipient ON reward_grants (recipient_profile_id, state);

            CREATE TABLE IF NOT EXISTS reward_grant_components (
                component_id VARCHAR(36) NOT NULL PRIMARY KEY,
                grant_id VARCHAR(36) NOT NULL,
                component_index INT NOT NULL,
                component_operation_id VARCHAR(36) NOT NULL,
                component_type VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                journal_operation_id VARCHAR(36) NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_reward_comp_grant FOREIGN KEY (grant_id)
                    REFERENCES reward_grants (grant_id) ON DELETE CASCADE,
                CONSTRAINT uq_reward_grant_comp_idx UNIQUE (grant_id, component_index),
                CONSTRAINT uq_reward_grant_comp_op UNIQUE (component_operation_id)
            );

            CREATE INDEX idx_reward_comp_grant ON reward_grant_components (grant_id, state);
            """;

    private static final String SQLITE_V18_DDL = """
            CREATE TABLE IF NOT EXISTS island_warps (
                warp_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                warp_name VARCHAR(32) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                yaw FLOAT NOT NULL DEFAULT 0.0,
                pitch FLOAT NOT NULL DEFAULT 0.0,
                icon_material VARCHAR(64) NOT NULL DEFAULT 'OAK_SIGN',
                category VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
                is_locked BOOLEAN NOT NULL DEFAULT 0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_warps_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE,
                CONSTRAINT uq_island_warps_name UNIQUE (island_id, warp_name)
            );

            CREATE INDEX IF NOT EXISTS idx_island_warps_island ON island_warps (island_id);
            CREATE INDEX IF NOT EXISTS idx_island_warps_category ON island_warps (category, is_locked);
            CREATE INDEX IF NOT EXISTS idx_island_warps_public ON island_warps (is_locked);

            CREATE TABLE IF NOT EXISTS island_bans (
                island_id VARCHAR(36) NOT NULL,
                banned_player_uuid VARCHAR(36) NOT NULL,
                banned_by_profile_id VARCHAR(36) NOT NULL,
                reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, banned_player_uuid),
                CONSTRAINT fk_island_bans_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_bans_player ON island_bans (banned_player_uuid);
            """;

    private static final String MYSQL_V18_DDL = """
            CREATE TABLE IF NOT EXISTS island_warps (
                warp_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                warp_name VARCHAR(32) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                yaw FLOAT NOT NULL DEFAULT 0.0,
                pitch FLOAT NOT NULL DEFAULT 0.0,
                icon_material VARCHAR(64) NOT NULL DEFAULT 'OAK_SIGN',
                category VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
                is_locked BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_warps_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE,
                CONSTRAINT uq_island_warps_name UNIQUE (island_id, warp_name)
            );

            CREATE INDEX idx_island_warps_island ON island_warps (island_id);
            CREATE INDEX idx_island_warps_category ON island_warps (category, is_locked);
            CREATE INDEX idx_island_warps_public ON island_warps (is_locked);

            CREATE TABLE IF NOT EXISTS island_bans (
                island_id VARCHAR(36) NOT NULL,
                banned_player_uuid VARCHAR(36) NOT NULL,
                banned_by_profile_id VARCHAR(36) NOT NULL,
                reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, banned_player_uuid),
                CONSTRAINT fk_island_bans_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_bans_player ON island_bans (banned_player_uuid);
            """;

    private static final String POSTGRES_V18_DDL = """
            CREATE TABLE IF NOT EXISTS island_warps (
                warp_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                warp_name VARCHAR(32) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                x DOUBLE PRECISION NOT NULL,
                y DOUBLE PRECISION NOT NULL,
                z DOUBLE PRECISION NOT NULL,
                yaw REAL NOT NULL DEFAULT 0.0,
                pitch REAL NOT NULL DEFAULT 0.0,
                icon_material VARCHAR(64) NOT NULL DEFAULT 'OAK_SIGN',
                category VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
                is_locked BOOLEAN NOT NULL DEFAULT FALSE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_warps_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE,
                CONSTRAINT uq_island_warps_name UNIQUE (island_id, warp_name)
            );

            CREATE INDEX IF NOT EXISTS idx_island_warps_island ON island_warps (island_id);
            CREATE INDEX IF NOT EXISTS idx_island_warps_category ON island_warps (category, is_locked);
            CREATE INDEX IF NOT EXISTS idx_island_warps_public ON island_warps (is_locked);

            CREATE TABLE IF NOT EXISTS island_bans (
                island_id VARCHAR(36) NOT NULL,
                banned_player_uuid VARCHAR(36) NOT NULL,
                banned_by_profile_id VARCHAR(36) NOT NULL,
                reason VARCHAR(255) NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, banned_player_uuid),
                CONSTRAINT fk_island_bans_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_bans_player ON island_bans (banned_player_uuid);
            """;

    private static final String SQLITE_V19_DDL = """
            CREATE TABLE IF NOT EXISTS island_vault_pages (
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                page_version BIGINT NOT NULL DEFAULT 1,
                lease_epoch BIGINT NOT NULL DEFAULT 1,
                active_session_id VARCHAR(36) NULL,
                contents_nbt BLOB NOT NULL,
                last_modified_by VARCHAR(36) NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, page),
                CONSTRAINT fk_island_vault_pages_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_vault_pages_island ON island_vault_pages (island_id);

            CREATE TABLE IF NOT EXISTS vault_edit_sessions (
                session_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                base_page_version BIGINT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                escrow_journal TEXT NULL,
                opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                closed_at TIMESTAMP NULL,
                CONSTRAINT fk_vault_sessions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_vault_sessions_expiry ON vault_edit_sessions (state, expires_at);
            CREATE INDEX IF NOT EXISTS idx_vault_sessions_island ON vault_edit_sessions (island_id, page);

            CREATE TABLE IF NOT EXISTS vault_escrow_transfers (
                transfer_id VARCHAR(36) NOT NULL PRIMARY KEY,
                session_id VARCHAR(36) NOT NULL,
                source_type VARCHAR(16) NOT NULL,
                dest_type VARCHAR(16) NOT NULL,
                source_slot INT NOT NULL,
                dest_slot INT NOT NULL,
                source_before_fp VARCHAR(64) NOT NULL,
                source_after_fp VARCHAR(64) NOT NULL,
                dest_before_fp VARCHAR(64) NOT NULL,
                dest_after_fp VARCHAR(64) NOT NULL,
                source_expected_version BIGINT NOT NULL DEFAULT 0,
                dest_expected_version BIGINT NOT NULL DEFAULT 0,
                source_container_version BIGINT NOT NULL DEFAULT 0,
                dest_container_version BIGINT NOT NULL DEFAULT 0,
                item_nbt BLOB NOT NULL,
                quantity INT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'INTENT',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_vault_escrow_session FOREIGN KEY (session_id)
                    REFERENCES vault_edit_sessions (session_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_vault_escrow_session ON vault_escrow_transfers (session_id, state);

            CREATE TABLE IF NOT EXISTS vault_audit_logs (
                log_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                actor_profile_id VARCHAR(36) NOT NULL,
                action_type VARCHAR(16) NOT NULL,
                slot INT NOT NULL,
                item_summary VARCHAR(128) NOT NULL,
                quantity INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_vault_audit_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_vault_audit_island ON vault_audit_logs (island_id, created_at);
            """;

    private static final String MYSQL_V19_DDL = """
            CREATE TABLE IF NOT EXISTS island_vault_pages (
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                page_version BIGINT NOT NULL DEFAULT 1,
                lease_epoch BIGINT NOT NULL DEFAULT 1,
                active_session_id VARCHAR(36) NULL,
                contents_nbt MEDIUMBLOB NOT NULL,
                last_modified_by VARCHAR(36) NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, page),
                CONSTRAINT fk_island_vault_pages_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_island_vault_pages_island ON island_vault_pages (island_id);

            CREATE TABLE IF NOT EXISTS vault_edit_sessions (
                session_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                base_page_version BIGINT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                escrow_journal TEXT NULL,
                opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                closed_at TIMESTAMP NULL,
                CONSTRAINT fk_vault_sessions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_vault_sessions_expiry ON vault_edit_sessions (state, expires_at);
            CREATE INDEX idx_vault_sessions_island ON vault_edit_sessions (island_id, page);

            CREATE TABLE IF NOT EXISTS vault_escrow_transfers (
                transfer_id VARCHAR(36) NOT NULL PRIMARY KEY,
                session_id VARCHAR(36) NOT NULL,
                source_type VARCHAR(16) NOT NULL,
                dest_type VARCHAR(16) NOT NULL,
                source_slot INT NOT NULL,
                dest_slot INT NOT NULL,
                source_before_fp VARCHAR(64) NOT NULL,
                source_after_fp VARCHAR(64) NOT NULL,
                dest_before_fp VARCHAR(64) NOT NULL,
                dest_after_fp VARCHAR(64) NOT NULL,
                source_expected_version BIGINT NOT NULL DEFAULT 0,
                dest_expected_version BIGINT NOT NULL DEFAULT 0,
                source_container_version BIGINT NOT NULL DEFAULT 0,
                dest_container_version BIGINT NOT NULL DEFAULT 0,
                item_nbt MEDIUMBLOB NOT NULL,
                quantity INT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'INTENT',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_vault_escrow_session FOREIGN KEY (session_id)
                    REFERENCES vault_edit_sessions (session_id) ON DELETE CASCADE
            );

            CREATE INDEX idx_vault_escrow_session ON vault_escrow_transfers (session_id, state);

            CREATE TABLE IF NOT EXISTS vault_audit_logs (
                log_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                actor_profile_id VARCHAR(36) NOT NULL,
                action_type VARCHAR(16) NOT NULL,
                slot INT NOT NULL,
                item_summary VARCHAR(128) NOT NULL,
                quantity INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_vault_audit_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX idx_vault_audit_island ON vault_audit_logs (island_id, created_at);
            """;

    private static final String POSTGRES_V19_DDL = """
            CREATE TABLE IF NOT EXISTS island_vault_pages (
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                page_version BIGINT NOT NULL DEFAULT 1,
                lease_epoch BIGINT NOT NULL DEFAULT 1,
                active_session_id VARCHAR(36) NULL,
                contents_nbt BYTEA NOT NULL,
                last_modified_by VARCHAR(36) NOT NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, page),
                CONSTRAINT fk_island_vault_pages_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_vault_pages_island ON island_vault_pages (island_id);

            CREATE TABLE IF NOT EXISTS vault_edit_sessions (
                session_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                base_page_version BIGINT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                escrow_journal TEXT NULL,
                opened_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                expires_at TIMESTAMP NOT NULL,
                closed_at TIMESTAMP NULL,
                CONSTRAINT fk_vault_sessions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_vault_sessions_expiry ON vault_edit_sessions (state, expires_at);
            CREATE INDEX IF NOT EXISTS idx_vault_sessions_island ON vault_edit_sessions (island_id, page);

            CREATE TABLE IF NOT EXISTS vault_escrow_transfers (
                transfer_id VARCHAR(36) NOT NULL PRIMARY KEY,
                session_id VARCHAR(36) NOT NULL,
                source_type VARCHAR(16) NOT NULL,
                dest_type VARCHAR(16) NOT NULL,
                source_slot INT NOT NULL,
                dest_slot INT NOT NULL,
                source_before_fp VARCHAR(64) NOT NULL,
                source_after_fp VARCHAR(64) NOT NULL,
                dest_before_fp VARCHAR(64) NOT NULL,
                dest_after_fp VARCHAR(64) NOT NULL,
                source_expected_version BIGINT NOT NULL DEFAULT 0,
                dest_expected_version BIGINT NOT NULL DEFAULT 0,
                source_container_version BIGINT NOT NULL DEFAULT 0,
                dest_container_version BIGINT NOT NULL DEFAULT 0,
                item_nbt BYTEA NOT NULL,
                quantity INT NOT NULL,
                state VARCHAR(32) NOT NULL DEFAULT 'INTENT',
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_vault_escrow_session FOREIGN KEY (session_id)
                    REFERENCES vault_edit_sessions (session_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_vault_escrow_session ON vault_escrow_transfers (session_id, state);

            CREATE TABLE IF NOT EXISTS vault_audit_logs (
                log_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                page INT NOT NULL,
                actor_profile_id VARCHAR(36) NOT NULL,
                action_type VARCHAR(16) NOT NULL,
                slot INT NOT NULL,
                item_summary VARCHAR(128) NOT NULL,
                quantity INT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_vault_audit_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_vault_audit_island ON vault_audit_logs (island_id, created_at);
            """;

    private static final String SQLITE_V20_DDL = """
            CREATE TABLE IF NOT EXISTS island_missions (
                island_id VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                mission_id VARCHAR(64) NOT NULL,
                progress_count BIGINT NOT NULL DEFAULT 0,
                completed SMALLINT NOT NULL DEFAULT 0,
                completed_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, profile_id, mission_id),
                CONSTRAINT fk_island_missions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_missions_island_profile ON island_missions (island_id, profile_id);
            """;

    private static final String MYSQL_V20_DDL = """
            CREATE TABLE IF NOT EXISTS island_missions (
                island_id VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                mission_id VARCHAR(64) NOT NULL,
                progress_count BIGINT NOT NULL DEFAULT 0,
                completed BOOLEAN NOT NULL DEFAULT FALSE,
                completed_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, profile_id, mission_id),
                INDEX idx_island_missions_island_profile (island_id, profile_id),
                CONSTRAINT fk_island_missions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V20_DDL = """
            CREATE TABLE IF NOT EXISTS island_missions (
                island_id VARCHAR(36) NOT NULL,
                profile_id VARCHAR(36) NOT NULL,
                mission_id VARCHAR(64) NOT NULL,
                progress_count BIGINT NOT NULL DEFAULT 0,
                completed BOOLEAN NOT NULL DEFAULT FALSE,
                completed_at TIMESTAMP WITH TIME ZONE NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, profile_id, mission_id),
                CONSTRAINT fk_island_missions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_missions_island_profile ON island_missions (island_id, profile_id);
            """;

    private static final String SQLITE_V21_DDL = """
            CREATE TABLE IF NOT EXISTS spiral_slot_pool (
                slot_index INTEGER NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                grid_x INTEGER NOT NULL,
                grid_z INTEGER NOT NULL,
                is_allocated SMALLINT NOT NULL DEFAULT 1,
                vacated_at TIMESTAMP NULL
            );

            CREATE INDEX IF NOT EXISTS idx_spiral_slot_pool_free ON spiral_slot_pool (is_allocated, slot_index ASC);
            """;

    private static final String MYSQL_V21_DDL = """
            CREATE TABLE IF NOT EXISTS spiral_slot_pool (
                slot_index BIGINT NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                grid_x INT NOT NULL,
                grid_z INT NOT NULL,
                is_allocated BOOLEAN NOT NULL DEFAULT TRUE,
                vacated_at TIMESTAMP NULL,
                INDEX idx_spiral_slot_pool_free (is_allocated, slot_index ASC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V21_DDL = """
            CREATE TABLE IF NOT EXISTS spiral_slot_pool (
                slot_index BIGINT NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                grid_x INT NOT NULL,
                grid_z INT NOT NULL,
                is_allocated BOOLEAN NOT NULL DEFAULT TRUE,
                vacated_at TIMESTAMP WITH TIME ZONE NULL
            );

            CREATE INDEX IF NOT EXISTS idx_spiral_slot_pool_free ON spiral_slot_pool (is_allocated, slot_index ASC);
            """;

    private static final String SQLITE_V22_DDL = """
            CREATE TABLE IF NOT EXISTS player_anti_abuse_records (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                last_island_reset_at TIMESTAMP NULL,
                resets_today_count INTEGER NOT NULL DEFAULT 0,
                reset_window_start TIMESTAMP NULL,
                coop_cooldown_expires_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS island_quarantines (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                quarantined_until TIMESTAMP NOT NULL,
                quarantine_reason VARCHAR(255) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_quarantines_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_quarantines_until ON island_quarantines (quarantined_until);
            """;

    private static final String MYSQL_V22_DDL = """
            CREATE TABLE IF NOT EXISTS player_anti_abuse_records (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                last_island_reset_at TIMESTAMP NULL,
                resets_today_count INT NOT NULL DEFAULT 0,
                reset_window_start TIMESTAMP NULL,
                coop_cooldown_expires_at TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS island_quarantines (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                quarantined_until TIMESTAMP NOT NULL,
                quarantine_reason VARCHAR(255) NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_island_quarantines_until (quarantined_until),
                CONSTRAINT fk_island_quarantines_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V22_DDL = """
            CREATE TABLE IF NOT EXISTS player_anti_abuse_records (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                last_island_reset_at TIMESTAMP WITH TIME ZONE NULL,
                resets_today_count INT NOT NULL DEFAULT 0,
                reset_window_start TIMESTAMP WITH TIME ZONE NULL,
                coop_cooldown_expires_at TIMESTAMP WITH TIME ZONE NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE IF NOT EXISTS island_quarantines (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                quarantined_until TIMESTAMP WITH TIME ZONE NOT NULL,
                quarantine_reason VARCHAR(255) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_quarantines_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_quarantines_until ON island_quarantines (quarantined_until);
            """;
}
