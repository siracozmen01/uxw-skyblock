package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V13_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V14_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V15_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class GameplaySchemaMigrationsV13ToV15 {

    private GameplaySchemaMigrationsV13ToV15() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v13Migration(dialect), v14Migration(dialect), v15Migration(dialect));
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
}
