package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V38_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V38: the single writer of a game mode instance, and of a root a mode owns, leased the way an
 * island's is in {@code island_authorities}, with the same column types on every engine.
 */
final class GameModeSchemaMigrationsV38 {

    private GameModeSchemaMigrationsV38() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(38, V38_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS game_mode_instance_authorities (
                        instance_id TEXT NOT NULL PRIMARY KEY,
                        authoritative_node TEXT NOT NULL,
                        authority_epoch INTEGER NOT NULL DEFAULT 1,
                        state TEXT NOT NULL DEFAULT 'ACTIVE',
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_instance_auth_instance FOREIGN KEY (instance_id)
                            REFERENCES game_mode_instances (id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_instance_auth_node
                        ON game_mode_instance_authorities (authoritative_node);
                    CREATE TABLE IF NOT EXISTS mode_owned_authorities (
                        provider_id TEXT NOT NULL,
                        root_key TEXT NOT NULL,
                        authoritative_node TEXT NOT NULL,
                        authority_epoch INTEGER NOT NULL DEFAULT 1,
                        state TEXT NOT NULL DEFAULT 'ACTIVE',
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (provider_id, root_key)
                    );
                    CREATE INDEX IF NOT EXISTS idx_mode_owned_auth_node
                        ON mode_owned_authorities (authoritative_node);
                    """);
            case MYSQL -> new Migration(38, V38_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS game_mode_instance_authorities (
                        instance_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        authoritative_node VARCHAR(64) NOT NULL,
                        authority_epoch BIGINT NOT NULL DEFAULT 1,
                        state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_instance_auth_node (authoritative_node),
                        CONSTRAINT fk_instance_auth_instance FOREIGN KEY (instance_id)
                            REFERENCES game_mode_instances (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    CREATE TABLE IF NOT EXISTS mode_owned_authorities (
                        provider_id VARCHAR(64) NOT NULL,
                        root_key VARCHAR(128) NOT NULL,
                        authoritative_node VARCHAR(64) NOT NULL,
                        authority_epoch BIGINT NOT NULL DEFAULT 1,
                        state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (provider_id, root_key),
                        INDEX idx_mode_owned_auth_node (authoritative_node)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(38, V38_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS game_mode_instance_authorities (
                        instance_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        authoritative_node VARCHAR(64) NOT NULL,
                        authority_epoch BIGINT NOT NULL DEFAULT 1,
                        state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_instance_auth_instance FOREIGN KEY (instance_id)
                            REFERENCES game_mode_instances (id) ON DELETE CASCADE
                    );
                    CREATE INDEX IF NOT EXISTS idx_instance_auth_node
                        ON game_mode_instance_authorities (authoritative_node);
                    CREATE TABLE IF NOT EXISTS mode_owned_authorities (
                        provider_id VARCHAR(64) NOT NULL,
                        root_key VARCHAR(128) NOT NULL,
                        authoritative_node VARCHAR(64) NOT NULL,
                        authority_epoch BIGINT NOT NULL DEFAULT 1,
                        state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                        lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (provider_id, root_key)
                    );
                    CREATE INDEX IF NOT EXISTS idx_mode_owned_auth_node
                        ON mode_owned_authorities (authoritative_node);
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V38: " + dialect);
        };
    }
}
