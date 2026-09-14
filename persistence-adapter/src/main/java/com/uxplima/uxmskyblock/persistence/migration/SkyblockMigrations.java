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
    public static final int LATEST_VERSION = 1;

    /** Human-readable description of migration V1. */
    public static final String V1_DESCRIPTION = "create player accounts profiles and sessions";

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
        return List.of(v1Migration(dialect));
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
}
