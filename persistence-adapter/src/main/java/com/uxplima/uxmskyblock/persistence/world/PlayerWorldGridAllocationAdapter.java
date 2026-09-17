package com.uxplima.uxmskyblock.persistence.world;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.IslandCoordinates;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Production persistence adapter implementing {@link WorldGridAllocationPort}.
 *
 * <p>Guarantees cluster-safe, crash-safe, restart-safe monotonic territorial sequence
 * allocation across SQLite, MariaDB, and PostgreSQL.
 */
public final class PlayerWorldGridAllocationAdapter implements WorldGridAllocationPort {

    private static final int MAX_RESERVATION_ATTEMPTS = 25;

    private final Database database;
    private final SpiralGridCoordinateAllocator allocator;

    public PlayerWorldGridAllocationAdapter(Database database, SpiralGridCoordinateAllocator allocator) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
    }

    public PlayerWorldGridAllocationAdapter(Database database) {
        this(database, new SpiralGridCoordinateAllocator());
    }

    @Override
    public long reserveNextSequence(
            ServerNodeId nodeId, String worldName, int centerX, int centerZ, @Nullable IslandId islandId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        for (int attempt = 1; attempt <= MAX_RESERVATION_ATTEMPTS; attempt++) {
            long candidateSeq = queryNextCandidateSequence();
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            try (Connection conn = database.connection()) {
                boolean inserted =
                        tryInsertAllocation(conn, candidateSeq, worldName, centerX, centerZ, islandId, nodeId, now);
                if (inserted) {
                    return candidateSeq;
                }
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    backoff(attempt);
                    continue;
                }
                throw new IllegalStateException(
                        "Failed to reserve world grid sequence index at seq=" + candidateSeq, e);
            }
            backoff(attempt);
        }
        throw new IllegalStateException(
                "Exhausted " + MAX_RESERVATION_ATTEMPTS + " attempts to reserve world grid sequence index");
    }

    @Override
    public WorldGridAllocation allocateNext(ServerNodeId nodeId, String worldName, @Nullable IslandId islandId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        for (int attempt = 1; attempt <= MAX_RESERVATION_ATTEMPTS; attempt++) {
            long candidateSeq = queryNextCandidateSequence();
            IslandCoordinates coords = allocator.coordinatesForIndex(candidateSeq);
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

            try (Connection conn = database.connection()) {
                boolean inserted = tryInsertAllocation(
                        conn, candidateSeq, worldName, coords.x(), coords.z(), islandId, nodeId, now);
                if (inserted) {
                    return new WorldGridAllocation(
                            candidateSeq,
                            worldName,
                            coords.x(),
                            coords.z(),
                            Optional.ofNullable(islandId),
                            nodeId,
                            now);
                }
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    backoff(attempt);
                    continue;
                }
                throw new IllegalStateException("Failed to allocate world grid slot at seq=" + candidateSeq, e);
            }
            backoff(attempt);
        }
        throw new IllegalStateException(
                "Exhausted " + MAX_RESERVATION_ATTEMPTS + " attempts to allocate world grid slot");
    }

    @Override
    public Optional<WorldGridAllocation> findBySequenceIndex(long sequenceIndex) {
        String sql = """
                SELECT sequence_index, world_name, center_x, center_z, island_id, allocated_by_node, allocated_at
                FROM world_grid_allocations
                WHERE sequence_index = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sequenceIndex);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to query world grid allocation by sequenceIndex=" + sequenceIndex, e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<WorldGridAllocation> findByIslandId(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = """
                SELECT sequence_index, world_name, center_x, center_z, island_id, allocated_by_node, allocated_at
                FROM world_grid_allocations
                WHERE island_id = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query world grid allocation by islandId=" + islandId, e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<WorldGridAllocation> findByCoordinates(String worldName, int centerX, int centerZ) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        String sql = """
                SELECT sequence_index, world_name, center_x, center_z, island_id, allocated_by_node, allocated_at
                FROM world_grid_allocations
                WHERE world_name = ? AND center_x = ? AND center_z = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            stmt.setInt(2, centerX);
            stmt.setInt(3, centerZ);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to query world grid allocation by coords (" + worldName + "," + centerX + "," + centerZ
                            + ")",
                    e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Long> findMaxSequenceIndex(String worldName) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        String sql = "SELECT MAX(sequence_index) FROM world_grid_allocations WHERE world_name = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long val = rs.getLong(1);
                    if (!rs.wasNull()) {
                        return Optional.of(val);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query max sequence index for world=" + worldName, e);
        }
        return Optional.empty();
    }

    @Override
    public void bindIsland(long sequenceIndex, IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        String sql = "UPDATE world_grid_allocations SET island_id = ? WHERE sequence_index = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setLong(2, sequenceIndex);
            int updated = stmt.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException(
                        "No world grid allocation found for sequence_index=" + sequenceIndex);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to bind islandId=" + islandId + " to seq=" + sequenceIndex, e);
        }
    }

    private long queryNextCandidateSequence() {
        String sql = "SELECT MAX(sequence_index) FROM world_grid_allocations";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                long max = rs.getLong(1);
                if (!rs.wasNull()) {
                    return max + 1;
                }
            }
            return 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query max world grid sequence index", e);
        }
    }

    private boolean tryInsertAllocation(
            Connection conn,
            long sequenceIndex,
            String worldName,
            int centerX,
            int centerZ,
            @Nullable IslandId islandId,
            ServerNodeId nodeId,
            Instant allocatedAt)
            throws SQLException {
        String sql = """
                INSERT INTO world_grid_allocations (
                    sequence_index, world_name, center_x, center_z, island_id, allocated_by_node, allocated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, sequenceIndex);
            stmt.setString(2, worldName);
            stmt.setInt(3, centerX);
            stmt.setInt(4, centerZ);
            if (islandId != null) {
                stmt.setString(5, islandId.value().toString());
            } else {
                stmt.setNull(5, java.sql.Types.VARCHAR);
            }
            stmt.setString(6, nodeId.value());
            stmt.setTimestamp(7, Timestamp.from(allocatedAt));
            return stmt.executeUpdate() > 0;
        }
    }

    private WorldGridAllocation mapRow(ResultSet rs) throws SQLException {
        long seq = rs.getLong("sequence_index");
        String world = rs.getString("world_name");
        int cx = rs.getInt("center_x");
        int cz = rs.getInt("center_z");
        String islandStr = rs.getString("island_id");
        Optional<IslandId> islandId =
                islandStr != null ? Optional.of(IslandId.of(UUID.fromString(islandStr))) : Optional.empty();
        String nodeStr = rs.getString("allocated_by_node");
        Timestamp ts = rs.getTimestamp("allocated_at");
        Instant allocatedAt = ts != null ? ts.toInstant() : Instant.now();

        return new WorldGridAllocation(seq, world, cx, cz, islandId, new ServerNodeId(nodeStr), allocatedAt);
    }

    private boolean isUniqueViolation(SQLException e) {
        String sqlState = e.getSQLState();
        if (sqlState != null && (sqlState.startsWith("23") || sqlState.equals("40001"))) {
            return true;
        }
        int code = e.getErrorCode();
        if (code == 1062 || code == 19) { // 1062 is MySQL duplicate, 19 is SQLite constraint
            return true;
        }
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("unique") || lower.contains("duplicate") || lower.contains("primary key");
        }
        return false;
    }

    private void backoff(int attempt) {
        try {
            long delayMs = Math.min(100, 5L * attempt + (long) (Math.random() * 10));
            Thread.sleep(delayMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
