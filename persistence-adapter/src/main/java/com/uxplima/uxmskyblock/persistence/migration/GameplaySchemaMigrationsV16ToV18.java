package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V16_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V17_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V18_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class GameplaySchemaMigrationsV16ToV18 {

    private GameplaySchemaMigrationsV16ToV18() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v16Migration(dialect), v17Migration(dialect), v18Migration(dialect));
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
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
}
