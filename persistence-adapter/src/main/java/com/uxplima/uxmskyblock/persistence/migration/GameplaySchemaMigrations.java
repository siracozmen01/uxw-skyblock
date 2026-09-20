package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V13_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V14_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V15_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V16_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V17_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V18_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V19_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V20_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Gameplay schema migrations (V13 - V20).
 * Covers seasons, snapshots, payouts, social ratings, guestbook reviews,
 * subject visits, bookmarks, alliances, invites, temporary access grants,
 * reward grants/components, warps, bans, vault pages/sessions/escrows/logs,
 * and island missions.
 */
final class GameplaySchemaMigrations {

    private GameplaySchemaMigrations() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(
                v13Migration(dialect),
                v14Migration(dialect),
                v15Migration(dialect),
                v16Migration(dialect),
                v17Migration(dialect),
                v18Migration(dialect),
                v19Migration(dialect),
                v20Migration(dialect));
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
