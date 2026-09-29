package com.uxplima.uxmskyblock.persistence.chunkblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.chunkblock.ChunkTerritoryPort;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkPos;
import com.uxplima.uxmskyblock.core.domain.chunkblock.ChunkTerritory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/** The {@code chunkblock_territory_claims} table. */
public final class SqlChunkTerritoryAdapter implements ChunkTerritoryPort {

    private final DataSource dataSource;

    public SqlChunkTerritoryAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Map<IslandId, ChunkTerritory> findAll() {
        Map<IslandId, ChunkPos> origins = new HashMap<>();
        Map<IslandId, List<ChunkPos>> opened = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT island_id, chunk_x, chunk_z, unlock_order "
                        + "FROM chunkblock_territory_claims ORDER BY island_id, unlock_order");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                IslandId islandId = IslandId.of(java.util.UUID.fromString(rs.getString(1)));
                ChunkPos chunk = new ChunkPos(rs.getInt(2), rs.getInt(3));
                if (rs.getInt(4) == 0) {
                    origins.put(islandId, chunk);
                } else {
                    opened.computeIfAbsent(islandId, id -> new ArrayList<>()).add(chunk);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the ChunkBlock islands", e);
        }
        Map<IslandId, ChunkTerritory> all = new HashMap<>();
        origins.forEach((islandId, origin) ->
                all.put(islandId, new ChunkTerritory(origin, opened.getOrDefault(islandId, List.of()))));
        return all;
    }

    @Override
    public Optional<ChunkTerritory> find(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT chunk_x, chunk_z, unlock_order "
                        + "FROM chunkblock_territory_claims WHERE island_id = ? ORDER BY unlock_order")) {
            ps.setString(1, islandId.value().toString());
            ChunkPos origin = null;
            List<ChunkPos> opened = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChunkPos chunk = new ChunkPos(rs.getInt(1), rs.getInt(2));
                    if (rs.getInt(3) == 0) {
                        origin = chunk;
                    } else {
                        opened.add(chunk);
                    }
                }
            }
            return origin == null ? Optional.empty() : Optional.of(new ChunkTerritory(origin, opened));
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the chunks of island " + islandId, e);
        }
    }

    @Override
    public void start(IslandId islandId, ChunkPos origin) {
        Objects.requireNonNull(origin, "origin must not be null");
        insert(islandId, origin, 0);
    }

    @Override
    public boolean open(IslandId islandId, ChunkPos chunk, int order) {
        Objects.requireNonNull(chunk, "chunk must not be null");
        if (order < 1) {
            throw new IllegalArgumentException("the first opened chunk is number 1: " + order);
        }
        return insert(islandId, chunk, order);
    }

    @Override
    public void close(IslandId islandId, List<ChunkPos> chunks) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(chunks, "chunks must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM chunkblock_territory_claims "
                        + "WHERE island_id = ? AND chunk_x = ? AND chunk_z = ? AND unlock_order > 0")) {
            for (ChunkPos chunk : chunks) {
                ps.setString(1, islandId.value().toString());
                ps.setInt(2, chunk.x());
                ps.setInt(3, chunk.z());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not close chunks of island " + islandId, e);
        }
    }

    private boolean insert(IslandId islandId, ChunkPos chunk, int order) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("INSERT INTO chunkblock_territory_claims "
                        + "(island_id, chunk_x, chunk_z, unlock_order) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, islandId.value().toString());
            ps.setInt(2, chunk.x());
            ps.setInt(3, chunk.z());
            ps.setInt(4, order);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // Class 23 is an integrity constraint: the place or the chunk was taken first. The SQLite
            // driver names no class and says SQLITE_CONSTRAINT, code 19, instead.
            String state = e.getSQLState();
            if ((state != null && state.startsWith("23")) || (state == null && e.getErrorCode() == 19)) {
                return false;
            }
            throw new IllegalStateException("Could not open a chunk of island " + islandId, e);
        }
    }
}
