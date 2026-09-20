package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V21_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V22_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V23_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V24_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V25_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V26_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V27_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V28_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Operations schema migrations (V21 - V28).
 * Covers spiral slot pool coordinate recycling, player anti-abuse records,
 * island quarantines, island boosters, island bankruptcies, game mode instances,
 * primary gameplay root references, enterprise activity events, notifications,
 * island homes, island dimensions, profile cosmetics, and island recycle operations.
 */
final class OperationsSchemaMigrations {

    private OperationsSchemaMigrations() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(
                v21Migration(dialect),
                v22Migration(dialect),
                v23Migration(dialect),
                v24Migration(dialect),
                v25Migration(dialect),
                v26Migration(dialect),
                v27Migration(dialect),
                v28Migration(dialect));
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

    private static Migration v23Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(23, V23_DESCRIPTION, SQLITE_V23_DDL);
            case MYSQL -> new Migration(23, V23_DESCRIPTION, MYSQL_V23_DDL);
            case POSTGRES -> new Migration(23, V23_DESCRIPTION, POSTGRES_V23_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v24Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(24, V24_DESCRIPTION, SQLITE_V24_DDL);
            case MYSQL -> new Migration(24, V24_DESCRIPTION, MYSQL_V24_DDL);
            case POSTGRES -> new Migration(24, V24_DESCRIPTION, POSTGRES_V24_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v25Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(25, V25_DESCRIPTION, SQLITE_V25_DDL);
            case MYSQL -> new Migration(25, V25_DESCRIPTION, MYSQL_V25_DDL);
            case POSTGRES -> new Migration(25, V25_DESCRIPTION, POSTGRES_V25_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v26Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(26, V26_DESCRIPTION, SQLITE_V26_DDL);
            case MYSQL -> new Migration(26, V26_DESCRIPTION, MYSQL_V26_DDL);
            case POSTGRES -> new Migration(26, V26_DESCRIPTION, POSTGRES_V26_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v27Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(27, V27_DESCRIPTION, SQLITE_V27_DDL);
            case MYSQL -> new Migration(27, V27_DESCRIPTION, MYSQL_V27_DDL);
            case POSTGRES -> new Migration(27, V27_DESCRIPTION, POSTGRES_V27_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

    private static Migration v28Migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(28, V28_DESCRIPTION, SQLITE_V28_DDL);
            case MYSQL -> new Migration(28, V28_DESCRIPTION, MYSQL_V28_DDL);
            case POSTGRES -> new Migration(28, V28_DESCRIPTION, POSTGRES_V28_DDL);
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock V1 production persistence supports SQLite, MariaDB (upstream MYSQL identifier), and PostgreSQL.");
        };
    }

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

    private static final String SQLITE_V23_DDL = """
            CREATE TABLE IF NOT EXISTS island_boosters (
                booster_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                multiplier DOUBLE NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                paused_at TIMESTAMP NULL,
                remaining_seconds BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_island_boosters_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_boosters_lookup ON island_boosters (island_id, category, expires_at);
            """;

    private static final String MYSQL_V23_DDL = """
            CREATE TABLE IF NOT EXISTS island_boosters (
                booster_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                multiplier DOUBLE NOT NULL,
                expires_at TIMESTAMP NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                paused_at TIMESTAMP NULL,
                remaining_seconds BIGINT NOT NULL DEFAULT 0,
                INDEX idx_island_boosters_lookup (island_id, category, expires_at),
                CONSTRAINT fk_island_boosters_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V23_DDL = """
            CREATE TABLE IF NOT EXISTS island_boosters (
                booster_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                multiplier DOUBLE PRECISION NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                paused_at TIMESTAMP WITH TIME ZONE NULL,
                remaining_seconds BIGINT NOT NULL DEFAULT 0,
                CONSTRAINT fk_island_boosters_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_boosters_lookup ON island_boosters (island_id, category, expires_at);
            """;

    private static final String SQLITE_V24_DDL = """
            CREATE TABLE IF NOT EXISTS island_bankruptcies (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                status VARCHAR(32) NOT NULL,
                debt_minor_units BIGINT NOT NULL DEFAULT 0,
                grace_until TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_bankruptcies_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_bankruptcies_status ON island_bankruptcies (status);
            """;

    private static final String MYSQL_V24_DDL = """
            CREATE TABLE IF NOT EXISTS island_bankruptcies (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                status VARCHAR(32) NOT NULL,
                debt_minor_units BIGINT NOT NULL DEFAULT 0,
                grace_until TIMESTAMP NULL,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_island_bankruptcies_status (status),
                CONSTRAINT fk_island_bankruptcies_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V24_DDL = """
            CREATE TABLE IF NOT EXISTS island_bankruptcies (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                status VARCHAR(32) NOT NULL,
                debt_minor_units BIGINT NOT NULL DEFAULT 0,
                grace_until TIMESTAMP WITH TIME ZONE NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_bankruptcies_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_island_bankruptcies_status ON island_bankruptcies (status);
            """;

    private static final String SQLITE_V25_DDL = """
            CREATE TABLE IF NOT EXISTS game_mode_instances (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                profile_id VARCHAR(36) NOT NULL,
                game_mode_type VARCHAR(32) NOT NULL,
                ruleset_config TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_game_mode_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_game_mode_profile ON game_mode_instances (profile_id);

            CREATE TABLE IF NOT EXISTS primary_gameplay_roots (
                game_mode_instance_id VARCHAR(36) NOT NULL,
                root_id VARCHAR(36) NOT NULL,
                root_type VARCHAR(32) NOT NULL,
                bound_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (game_mode_instance_id, root_id),
                CONSTRAINT fk_gameplay_root_instance FOREIGN KEY (game_mode_instance_id)
                    REFERENCES game_mode_instances (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_gameplay_roots_lookup ON primary_gameplay_roots (root_id, root_type);
            """;

    private static final String MYSQL_V25_DDL = """
            CREATE TABLE IF NOT EXISTS game_mode_instances (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                profile_id VARCHAR(36) NOT NULL,
                game_mode_type VARCHAR(32) NOT NULL,
                ruleset_config TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_game_mode_profile (profile_id),
                CONSTRAINT fk_game_mode_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS primary_gameplay_roots (
                game_mode_instance_id VARCHAR(36) NOT NULL,
                root_id VARCHAR(36) NOT NULL,
                root_type VARCHAR(32) NOT NULL,
                bound_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (game_mode_instance_id, root_id),
                INDEX idx_gameplay_roots_lookup (root_id, root_type),
                CONSTRAINT fk_gameplay_root_instance FOREIGN KEY (game_mode_instance_id)
                    REFERENCES game_mode_instances (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V25_DDL = """
            CREATE TABLE IF NOT EXISTS game_mode_instances (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                profile_id VARCHAR(36) NOT NULL,
                game_mode_type VARCHAR(32) NOT NULL,
                ruleset_config TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_game_mode_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_game_mode_profile ON game_mode_instances (profile_id);

            CREATE TABLE IF NOT EXISTS primary_gameplay_roots (
                game_mode_instance_id VARCHAR(36) NOT NULL,
                root_id VARCHAR(36) NOT NULL,
                root_type VARCHAR(32) NOT NULL,
                bound_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (game_mode_instance_id, root_id),
                CONSTRAINT fk_gameplay_root_instance FOREIGN KEY (game_mode_instance_id)
                    REFERENCES game_mode_instances (id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_gameplay_roots_lookup ON primary_gameplay_roots (root_id, root_type);
            """;

    private static final String SQLITE_V26_DDL = """
            CREATE TABLE IF NOT EXISTS activity_events (
                event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                instance_id VARCHAR(64) NOT NULL,
                actor_profile_id VARCHAR(36) NULL,
                event_type VARCHAR(32) NOT NULL,
                visibility VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_activity_events_instance_created ON activity_events (instance_id, created_at);
            CREATE INDEX IF NOT EXISTS idx_activity_events_visibility ON activity_events (instance_id, visibility, created_at);

            CREATE TABLE IF NOT EXISTS notifications (
                notification_id VARCHAR(36) NOT NULL PRIMARY KEY,
                recipient_profile_id VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                is_read BOOLEAN NOT NULL DEFAULT 0,
                read_at TIMESTAMP NULL,
                expires_at TIMESTAMP NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient_profile_id, is_read, created_at);

            CREATE TABLE IF NOT EXISTS island_homes (
                home_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_profile_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                home_name VARCHAR(64) NOT NULL,
                home_scope VARCHAR(32) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                yaw FLOAT NOT NULL DEFAULT 0.0,
                pitch FLOAT NOT NULL DEFAULT 0.0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_homes_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE,
                CONSTRAINT fk_island_homes_owner FOREIGN KEY (owner_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE,
                CONSTRAINT uq_island_homes_owner_name UNIQUE (owner_profile_id, home_name)
            );

            CREATE INDEX IF NOT EXISTS idx_island_homes_island ON island_homes (island_id, home_scope);
            CREATE INDEX IF NOT EXISTS idx_island_homes_owner ON island_homes (owner_profile_id);
            """;

    private static final String MYSQL_V26_DDL = """
            CREATE TABLE IF NOT EXISTS activity_events (
                event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                instance_id VARCHAR(64) NOT NULL,
                actor_profile_id VARCHAR(36) NULL,
                event_type VARCHAR(32) NOT NULL,
                visibility VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_activity_events_instance_created (instance_id, created_at),
                INDEX idx_activity_events_visibility (instance_id, visibility, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS notifications (
                notification_id VARCHAR(36) NOT NULL PRIMARY KEY,
                recipient_profile_id VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                is_read BOOLEAN NOT NULL DEFAULT FALSE,
                read_at TIMESTAMP NULL,
                expires_at TIMESTAMP NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_notifications_recipient (recipient_profile_id, is_read, created_at),
                CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS island_homes (
                home_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_profile_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                home_name VARCHAR(64) NOT NULL,
                home_scope VARCHAR(32) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                yaw FLOAT NOT NULL DEFAULT 0.0,
                pitch FLOAT NOT NULL DEFAULT 0.0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                CONSTRAINT uq_island_homes_owner_name UNIQUE (owner_profile_id, home_name),
                INDEX idx_island_homes_island (island_id, home_scope),
                INDEX idx_island_homes_owner (owner_profile_id),
                CONSTRAINT fk_island_homes_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE,
                CONSTRAINT fk_island_homes_owner FOREIGN KEY (owner_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V26_DDL = """
            CREATE TABLE IF NOT EXISTS activity_events (
                event_id VARCHAR(36) NOT NULL PRIMARY KEY,
                instance_id VARCHAR(64) NOT NULL,
                actor_profile_id VARCHAR(36) NULL,
                event_type VARCHAR(32) NOT NULL,
                visibility VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE INDEX IF NOT EXISTS idx_activity_events_instance_created ON activity_events (instance_id, created_at);
            CREATE INDEX IF NOT EXISTS idx_activity_events_visibility ON activity_events (instance_id, visibility, created_at);

            CREATE TABLE IF NOT EXISTS notifications (
                notification_id VARCHAR(36) NOT NULL PRIMARY KEY,
                recipient_profile_id VARCHAR(36) NOT NULL,
                category VARCHAR(32) NOT NULL,
                payload_type_id VARCHAR(64) NOT NULL,
                payload_schema_version INT NOT NULL DEFAULT 1,
                payload_data TEXT NOT NULL,
                is_read BOOLEAN NOT NULL DEFAULT FALSE,
                read_at TIMESTAMP WITH TIME ZONE NULL,
                expires_at TIMESTAMP WITH TIME ZONE NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );

            CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient_profile_id, is_read, created_at);

            CREATE TABLE IF NOT EXISTS island_homes (
                home_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_profile_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                home_name VARCHAR(64) NOT NULL,
                home_scope VARCHAR(32) NOT NULL,
                world_name VARCHAR(64) NOT NULL,
                x DOUBLE PRECISION NOT NULL,
                y DOUBLE PRECISION NOT NULL,
                z DOUBLE PRECISION NOT NULL,
                yaw REAL NOT NULL DEFAULT 0.0,
                pitch REAL NOT NULL DEFAULT 0.0,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT fk_island_homes_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE,
                CONSTRAINT fk_island_homes_owner FOREIGN KEY (owner_profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE,
                CONSTRAINT uq_island_homes_owner_name UNIQUE (owner_profile_id, home_name)
            );

            CREATE INDEX IF NOT EXISTS idx_island_homes_island ON island_homes (island_id, home_scope);
            CREATE INDEX IF NOT EXISTS idx_island_homes_owner ON island_homes (owner_profile_id);
            """;

    private static final String SQLITE_V27_DDL = """
            CREATE TABLE IF NOT EXISTS island_dimensions (
                island_id TEXT NOT NULL,
                dimension_type TEXT NOT NULL,
                generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, dimension_type)
            );
            CREATE INDEX IF NOT EXISTS idx_island_dimensions_island ON island_dimensions (island_id);
            """;

    private static final String MYSQL_V27_DDL = """
            CREATE TABLE IF NOT EXISTS island_dimensions (
                island_id VARCHAR(36) NOT NULL,
                dimension_type VARCHAR(32) NOT NULL,
                generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, dimension_type),
                INDEX idx_island_dimensions_island (island_id),
                CONSTRAINT fk_island_dimensions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V27_DDL = """
            CREATE TABLE IF NOT EXISTS island_dimensions (
                island_id VARCHAR(36) NOT NULL,
                dimension_type VARCHAR(32) NOT NULL,
                generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (island_id, dimension_type),
                CONSTRAINT fk_island_dimensions_island FOREIGN KEY (island_id)
                    REFERENCES islands (id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_island_dimensions_island ON island_dimensions (island_id);
            """;

    private static final String SQLITE_V28_DDL = """
            CREATE TABLE IF NOT EXISTS profile_cosmetics (
                profile_id TEXT NOT NULL,
                cosmetic_id TEXT NOT NULL,
                unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                granted_by TEXT NULL,
                PRIMARY KEY (profile_id, cosmetic_id),
                CONSTRAINT fk_profile_cosmetics_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_profile_cosmetics_profile ON profile_cosmetics (profile_id);

            CREATE TABLE IF NOT EXISTS island_recycle_operations (
                operation_id TEXT NOT NULL PRIMARY KEY,
                island_id TEXT NOT NULL,
                initiator_uuid TEXT NOT NULL,
                target_slot INTEGER NOT NULL,
                state TEXT NOT NULL,
                backup_path TEXT NULL,
                error_message TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            CREATE INDEX IF NOT EXISTS idx_recycle_ops_island ON island_recycle_operations (island_id);
            CREATE INDEX IF NOT EXISTS idx_recycle_ops_state ON island_recycle_operations (state);
            """;

    private static final String MYSQL_V28_DDL = """
            CREATE TABLE IF NOT EXISTS profile_cosmetics (
                profile_id VARCHAR(36) NOT NULL,
                cosmetic_id VARCHAR(64) NOT NULL,
                unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                granted_by VARCHAR(64) NULL,
                PRIMARY KEY (profile_id, cosmetic_id),
                INDEX idx_profile_cosmetics_profile (profile_id),
                CONSTRAINT fk_profile_cosmetics_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS island_recycle_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                initiator_uuid VARCHAR(36) NOT NULL,
                target_slot INT NOT NULL,
                state VARCHAR(32) NOT NULL,
                backup_path VARCHAR(255) NULL,
                error_message TEXT NULL,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_recycle_ops_island (island_id),
                INDEX idx_recycle_ops_state (state)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

    private static final String POSTGRES_V28_DDL = """
            CREATE TABLE IF NOT EXISTS profile_cosmetics (
                profile_id VARCHAR(36) NOT NULL,
                cosmetic_id VARCHAR(64) NOT NULL,
                unlocked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                granted_by VARCHAR(64) NULL,
                PRIMARY KEY (profile_id, cosmetic_id),
                CONSTRAINT fk_profile_cosmetics_profile FOREIGN KEY (profile_id)
                    REFERENCES player_profiles (profile_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS idx_profile_cosmetics_profile ON profile_cosmetics (profile_id);

            CREATE TABLE IF NOT EXISTS island_recycle_operations (
                operation_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                initiator_uuid VARCHAR(36) NOT NULL,
                target_slot INT NOT NULL,
                state VARCHAR(32) NOT NULL,
                backup_path VARCHAR(255) NULL,
                error_message TEXT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            CREATE INDEX IF NOT EXISTS idx_recycle_ops_island ON island_recycle_operations (island_id);
            CREATE INDEX IF NOT EXISTS idx_recycle_ops_state ON island_recycle_operations (state);
            """;
}
