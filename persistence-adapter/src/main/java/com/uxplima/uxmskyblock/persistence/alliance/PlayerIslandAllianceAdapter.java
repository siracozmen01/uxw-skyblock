package com.uxplima.uxmskyblock.persistence.alliance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceId;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteId;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Production SQL implementation of {@link IslandAllianceStoragePort} managing
 * bilateral island alliances and diplomatic invites across SQLite, MySQL, and PostgreSQL.
 */
public final class PlayerIslandAllianceAdapter implements IslandAllianceStoragePort {

    private final Database database;

    public PlayerIslandAllianceAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void saveAlliance(IslandAlliance alliance) {
        Objects.requireNonNull(alliance, "alliance must not be null");

        IslandId first =
                alliance.islandA().compareTo(alliance.islandB()) <= 0 ? alliance.islandA() : alliance.islandB();
        IslandId second = first.equals(alliance.islandA()) ? alliance.islandB() : alliance.islandA();

        String checkSql = """
                SELECT 1 FROM island_alliances
                WHERE island_a_id = ? AND island_b_id = ?
                """;
        String insertSql = """
                INSERT INTO island_alliances (alliance_id, island_a_id, island_b_id, created_at)
                VALUES (?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, first.value().toString());
                checkStmt.setString(2, second.value().toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (!exists) {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, alliance.id().value().toString());
                    insertStmt.setString(2, first.value().toString());
                    insertStmt.setString(3, second.value().toString());
                    insertStmt.setTimestamp(4, Timestamp.from(alliance.createdAt()));
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException("Failed to save island alliance: " + alliance, e);
        }
    }

    @Override
    public void removeAlliance(IslandId islandA, IslandId islandB) {
        Objects.requireNonNull(islandA, "islandA must not be null");
        Objects.requireNonNull(islandB, "islandB must not be null");

        String sql = """
                DELETE FROM island_alliances
                WHERE (island_a_id = ? AND island_b_id = ?)
                   OR (island_a_id = ? AND island_b_id = ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandA.value().toString());
            stmt.setString(2, islandB.value().toString());
            stmt.setString(3, islandB.value().toString());
            stmt.setString(4, islandA.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new AlliancePersistenceException(
                    "Failed to remove alliance between " + islandA + " and " + islandB, e);
        }
    }

    @Override
    public boolean areAllied(IslandId islandA, IslandId islandB) {
        Objects.requireNonNull(islandA, "islandA must not be null");
        Objects.requireNonNull(islandB, "islandB must not be null");

        if (islandA.equals(islandB)) {
            return false;
        }

        String sql = """
                SELECT 1 FROM island_alliances
                WHERE (island_a_id = ? AND island_b_id = ?)
                   OR (island_a_id = ? AND island_b_id = ?)
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandA.value().toString());
            stmt.setString(2, islandB.value().toString());
            stmt.setString(3, islandB.value().toString());
            stmt.setString(4, islandA.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException(
                    "Failed to check alliance between " + islandA + " and " + islandB, e);
        }
    }

    @Override
    public List<IslandAlliance> findAlliances(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT alliance_id, island_a_id, island_b_id, created_at
                FROM island_alliances
                WHERE island_a_id = ? OR island_b_id = ?
                ORDER BY created_at ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                List<IslandAlliance> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new IslandAlliance(
                            AllianceId.fromString(rs.getString("alliance_id")),
                            IslandId.fromString(rs.getString("island_a_id")),
                            IslandId.fromString(rs.getString("island_b_id")),
                            rs.getTimestamp("created_at").toInstant()));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException("Failed to find alliances for island " + islandId, e);
        }
    }

    @Override
    public int countAlliances(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");

        String sql = """
                SELECT COUNT(*) FROM island_alliances
                WHERE island_a_id = ? OR island_b_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, islandId.value().toString());
            stmt.setString(2, islandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException("Failed to count alliances for island " + islandId, e);
        }
    }

    @Override
    public void saveInvite(IslandAllianceInvite invite) {
        Objects.requireNonNull(invite, "invite must not be null");

        String checkSql = """
                SELECT 1 FROM island_alliance_invites
                WHERE sender_island_id = ? AND target_island_id = ?
                """;
        String updateSql = """
                UPDATE island_alliance_invites
                SET invite_id = ?, sender_profile_id = ?, created_at = ?, expires_at = ?
                WHERE sender_island_id = ? AND target_island_id = ?
                """;
        String insertSql = """
                INSERT INTO island_alliance_invites (
                    invite_id, sender_island_id, target_island_id, sender_profile_id, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, invite.senderIslandId().value().toString());
                checkStmt.setString(2, invite.targetIslandId().value().toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, invite.id().value().toString());
                    updateStmt.setString(2, invite.senderProfileId().value().toString());
                    updateStmt.setTimestamp(3, Timestamp.from(invite.createdAt()));
                    updateStmt.setTimestamp(4, Timestamp.from(invite.expiresAt()));
                    updateStmt.setString(5, invite.senderIslandId().value().toString());
                    updateStmt.setString(6, invite.targetIslandId().value().toString());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, invite.id().value().toString());
                    insertStmt.setString(2, invite.senderIslandId().value().toString());
                    insertStmt.setString(3, invite.targetIslandId().value().toString());
                    insertStmt.setString(4, invite.senderProfileId().value().toString());
                    insertStmt.setTimestamp(5, Timestamp.from(invite.createdAt()));
                    insertStmt.setTimestamp(6, Timestamp.from(invite.expiresAt()));
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException("Failed to save alliance invite: " + invite, e);
        }
    }

    @Override
    public Optional<IslandAllianceInvite> findInvite(IslandId senderIslandId, IslandId targetIslandId) {
        Objects.requireNonNull(senderIslandId, "senderIslandId must not be null");
        Objects.requireNonNull(targetIslandId, "targetIslandId must not be null");

        String sql = """
                SELECT invite_id, sender_island_id, target_island_id, sender_profile_id, created_at, expires_at
                FROM island_alliance_invites
                WHERE sender_island_id = ? AND target_island_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, senderIslandId.value().toString());
            stmt.setString(2, targetIslandId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new IslandAllianceInvite(
                            AllianceInviteId.fromString(rs.getString("invite_id")),
                            IslandId.fromString(rs.getString("sender_island_id")),
                            IslandId.fromString(rs.getString("target_island_id")),
                            ProfileId.fromString(rs.getString("sender_profile_id")),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant()));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException(
                    "Failed to find alliance invite from " + senderIslandId + " to " + targetIslandId, e);
        }
    }

    @Override
    public List<IslandAllianceInvite> findPendingInvites(IslandId targetIslandId, Instant now) {
        Objects.requireNonNull(targetIslandId, "targetIslandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        String sql = """
                SELECT invite_id, sender_island_id, target_island_id, sender_profile_id, created_at, expires_at
                FROM island_alliance_invites
                WHERE target_island_id = ? AND expires_at > ?
                ORDER BY created_at DESC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, targetIslandId.value().toString());
            stmt.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = stmt.executeQuery()) {
                List<IslandAllianceInvite> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new IslandAllianceInvite(
                            AllianceInviteId.fromString(rs.getString("invite_id")),
                            IslandId.fromString(rs.getString("sender_island_id")),
                            IslandId.fromString(rs.getString("target_island_id")),
                            ProfileId.fromString(rs.getString("sender_profile_id")),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("expires_at").toInstant()));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new AlliancePersistenceException("Failed to find pending invites for island " + targetIslandId, e);
        }
    }

    @Override
    public void deleteInvite(IslandId senderIslandId, IslandId targetIslandId) {
        Objects.requireNonNull(senderIslandId, "senderIslandId must not be null");
        Objects.requireNonNull(targetIslandId, "targetIslandId must not be null");

        String sql = """
                DELETE FROM island_alliance_invites
                WHERE sender_island_id = ? AND target_island_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, senderIslandId.value().toString());
            stmt.setString(2, targetIslandId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new AlliancePersistenceException(
                    "Failed to delete alliance invite from " + senderIslandId + " to " + targetIslandId, e);
        }
    }

    @Override
    public void purgeExpiredInvites(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        String sql = """
                DELETE FROM island_alliance_invites
                WHERE expires_at <= ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new AlliancePersistenceException("Failed to purge expired alliance invites at " + now, e);
        }
    }
}
