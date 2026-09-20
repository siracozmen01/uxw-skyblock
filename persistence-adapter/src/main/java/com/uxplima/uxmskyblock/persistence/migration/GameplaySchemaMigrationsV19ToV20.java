package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V19_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V20_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class GameplaySchemaMigrationsV19ToV20 {

    private GameplaySchemaMigrationsV19ToV20() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v19Migration(dialect), v20Migration(dialect));
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
}
