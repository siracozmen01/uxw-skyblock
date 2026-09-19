package com.uxplima.uxmskyblock.persistence.world;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.domain.world.RecycledSlot;

/**
 * Production persistence adapter implementing {@link SpiralSlotPoolPort}.
 *
 * <p>Provides dialect-safe atomic coordinate slot claiming, releasing, and allocation tracking
 * across SQLite, MySQL/MariaDB, and PostgreSQL.
 */
public final class SqlSpiralSlotPoolAdapter implements SpiralSlotPoolPort {

    private static final int MAX_CLAIM_ATTEMPTS = 5;

    private final Database database;

    public SqlSpiralSlotPoolAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public Optional<RecycledSlot> claimNextAvailableSlot(String worldName) {
        Objects.requireNonNull(worldName, "worldName must not be null");

        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            Optional<CandidateSlot> candidate = findLowestVacatedCandidate(worldName);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }

            CandidateSlot slot = candidate.get();
            boolean claimed = tryAtomicClaim(slot.slotIndex());
            if (claimed) {
                return Optional.of(
                        new RecycledSlot(slot.slotIndex(), slot.worldName(), slot.gridX(), slot.gridZ(), true, null));
            }
            // Another thread/node claimed this slot concurrently, retry with short exponential backoff
            backoff(attempt);
        }
        return Optional.empty();
    }

    @Override
    public void releaseSlot(long slotIndex, String worldName, int gridX, int gridZ) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Timestamp nowTs = Timestamp.from(now);

        String updateSql = """
                UPDATE spiral_slot_pool
                SET is_allocated = ?, vacated_at = ?
                WHERE slot_index = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            setBoolean(stmt, 1, false);
            stmt.setTimestamp(2, nowTs);
            stmt.setLong(3, slotIndex);
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                return;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update spiral_slot_pool for slotIndex=" + slotIndex, e);
        }

        // Row did not exist, insert fresh vacated record
        String insertSql = """
                INSERT INTO spiral_slot_pool (slot_index, world_name, grid_x, grid_z, is_allocated, vacated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setLong(1, slotIndex);
            stmt.setString(2, worldName);
            stmt.setInt(3, gridX);
            stmt.setInt(4, gridZ);
            setBoolean(stmt, 5, false);
            stmt.setTimestamp(6, nowTs);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // If inserted concurrently, ignore or re-attempt update
            try (Connection conn = database.connection();
                    PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                setBoolean(stmt, 1, false);
                stmt.setTimestamp(2, nowTs);
                stmt.setLong(3, slotIndex);
                stmt.executeUpdate();
            } catch (SQLException ex) {
                throw new IllegalStateException("Failed to release spiral slot for slotIndex=" + slotIndex, ex);
            }
        }
    }

    @Override
    public void recordAllocatedSlot(long slotIndex, String worldName, int gridX, int gridZ) {
        Objects.requireNonNull(worldName, "worldName must not be null");

        String updateSql = """
                UPDATE spiral_slot_pool
                SET is_allocated = ?, vacated_at = NULL
                WHERE slot_index = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(updateSql)) {
            setBoolean(stmt, 1, true);
            stmt.setLong(2, slotIndex);
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                return;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update spiral_slot_pool for slotIndex=" + slotIndex, e);
        }

        String insertSql = """
                INSERT INTO spiral_slot_pool (slot_index, world_name, grid_x, grid_z, is_allocated, vacated_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setLong(1, slotIndex);
            stmt.setString(2, worldName);
            stmt.setInt(3, gridX);
            stmt.setInt(4, gridZ);
            setBoolean(stmt, 5, true);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // Already present, ignore
        }
    }

    @Override
    public long countAvailableSlots(String worldName) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        String sql = """
                SELECT COUNT(*) FROM spiral_slot_pool
                WHERE world_name = ? AND (is_allocated = 0 OR is_allocated = false)
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count available spiral slots for world=" + worldName, e);
        }
        return 0L;
    }

    @Override
    public Optional<RecycledSlot> findBySlotIndex(long slotIndex) {
        String sql = """
                SELECT slot_index, world_name, grid_x, grid_z, is_allocated, vacated_at
                FROM spiral_slot_pool
                WHERE slot_index = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, slotIndex);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find spiral slot by slotIndex=" + slotIndex, e);
        }
        return Optional.empty();
    }

    private Optional<CandidateSlot> findLowestVacatedCandidate(String worldName) {
        String sql = """
                SELECT slot_index, world_name, grid_x, grid_z
                FROM spiral_slot_pool
                WHERE world_name = ? AND (is_allocated = 0 OR is_allocated = false)
                ORDER BY slot_index ASC
                LIMIT 1
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CandidateSlot(
                            rs.getLong("slot_index"),
                            rs.getString("world_name"),
                            rs.getInt("grid_x"),
                            rs.getInt("grid_z")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query lowest vacated candidate for world=" + worldName, e);
        }
        return Optional.empty();
    }

    private boolean tryAtomicClaim(long slotIndex) {
        String sql = """
                UPDATE spiral_slot_pool
                SET is_allocated = ?, vacated_at = NULL
                WHERE slot_index = ? AND (is_allocated = 0 OR is_allocated = false)
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            setBoolean(stmt, 1, true);
            stmt.setLong(2, slotIndex);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute atomic claim on slotIndex=" + slotIndex, e);
        }
    }

    private void setBoolean(PreparedStatement stmt, int parameterIndex, boolean value) throws SQLException {
        switch (database.dialect()) {
            case SQLITE -> stmt.setInt(parameterIndex, value ? 1 : 0);
            case MYSQL, POSTGRES -> stmt.setBoolean(parameterIndex, value);
            default -> stmt.setBoolean(parameterIndex, value);
        }
    }

    private boolean getBoolean(ResultSet rs, String columnLabel) throws SQLException {
        switch (database.dialect()) {
            case SQLITE -> {
                return rs.getInt(columnLabel) != 0;
            }
            default -> {
                return rs.getBoolean(columnLabel);
            }
        }
    }

    private RecycledSlot mapRow(ResultSet rs) throws SQLException {
        long slotIndex = rs.getLong("slot_index");
        String worldName = rs.getString("world_name");
        int gridX = rs.getInt("grid_x");
        int gridZ = rs.getInt("grid_z");
        boolean isAllocated = getBoolean(rs, "is_allocated");
        Timestamp vacatedTs = rs.getTimestamp("vacated_at");
        Instant vacatedAt = vacatedTs != null ? vacatedTs.toInstant() : null;

        return new RecycledSlot(slotIndex, worldName, gridX, gridZ, isAllocated, vacatedAt);
    }

    private static void backoff(int attempt) {
        try {
            long delayMs = Math.min(20L * (1L << Math.min(attempt, 4)), 200L);
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during spiral slot claim retry", e);
        }
    }

    private record CandidateSlot(long slotIndex, String worldName, int gridX, int gridZ) {}
}
