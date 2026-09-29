package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V36_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V36: the chunks each ChunkBlock island has open, in the order they were opened.
 *
 * <p>Order 0 is the chunk the island started with. The unique order is what keeps two players who open
 * a chunk at the same moment from both getting one: the second finds its place taken.
 */
final class GameModeSchemaMigrationsV36 {

    private GameModeSchemaMigrationsV36() {}

    static Migration migration(Dialect dialect) {
        return switch (dialect) {
            case SQLITE -> new Migration(36, V36_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS chunkblock_territory_claims (
                        island_id TEXT NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        unlock_order INTEGER NOT NULL,
                        unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, chunk_x, chunk_z),
                        CONSTRAINT uq_chunkblock_claim_order UNIQUE (island_id, unlock_order),
                        CONSTRAINT fk_chunkblock_claim_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case MYSQL -> new Migration(36, V36_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS chunkblock_territory_claims (
                        island_id VARCHAR(36) NOT NULL,
                        chunk_x INT NOT NULL,
                        chunk_z INT NOT NULL,
                        unlock_order INT NOT NULL,
                        unlocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, chunk_x, chunk_z),
                        CONSTRAINT uq_chunkblock_claim_order UNIQUE (island_id, unlock_order),
                        CONSTRAINT fk_chunkblock_claim_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                    """);
            case POSTGRES -> new Migration(36, V36_DESCRIPTION, """
                    CREATE TABLE IF NOT EXISTS chunkblock_territory_claims (
                        island_id VARCHAR(36) NOT NULL,
                        chunk_x INTEGER NOT NULL,
                        chunk_z INTEGER NOT NULL,
                        unlock_order INTEGER NOT NULL,
                        unlocked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (island_id, chunk_x, chunk_z),
                        CONSTRAINT uq_chunkblock_claim_order UNIQUE (island_id, unlock_order),
                        CONSTRAINT fk_chunkblock_claim_island FOREIGN KEY (island_id)
                            REFERENCES islands (id) ON DELETE CASCADE
                    );
                    """);
            case H2, GENERIC -> throw new IllegalArgumentException("Unsupported dialect for migration V36: " + dialect);
        };
    }
}
