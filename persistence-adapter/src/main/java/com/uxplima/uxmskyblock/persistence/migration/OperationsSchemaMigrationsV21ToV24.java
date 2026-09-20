package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V21_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V22_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V23_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V24_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class OperationsSchemaMigrationsV21ToV24 {

    private OperationsSchemaMigrationsV21ToV24() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v21Migration(dialect), v22Migration(dialect), v23Migration(dialect), v24Migration(dialect));
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

            CREATE INDEX idx_island_bankruptcies_status ON island_bankruptcies (status);
            """;
}
