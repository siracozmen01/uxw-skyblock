package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V10_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V11_DESCRIPTION;
import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V12_DESCRIPTION;

import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

final class CoreSchemaMigrationsV10ToV12 {

    private CoreSchemaMigrationsV10ToV12() {}

    static List<Migration> migrations(Dialect dialect) {
        return List.of(v10Migration(dialect), v11Migration(dialect), v12Migration(dialect));
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
}
