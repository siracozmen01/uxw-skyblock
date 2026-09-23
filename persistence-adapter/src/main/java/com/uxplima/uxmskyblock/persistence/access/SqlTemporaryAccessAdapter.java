package com.uxplima.uxmskyblock.persistence.access;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessStoragePort;
import com.uxplima.uxmskyblock.core.domain.access.GrantId;
import com.uxplima.uxmskyblock.core.domain.access.GrantState;
import com.uxplima.uxmskyblock.core.domain.access.TemporaryAccessGrant;
import com.uxplima.uxmskyblock.core.domain.access.TerminationPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.permission.PermissionKey;

/**
 * SQL persistence adapter for {@link TemporaryAccessStoragePort} implementing relational persistence
 * across SQLite, MySQL/MariaDB, and PostgreSQL.
 */
public final class SqlTemporaryAccessAdapter implements TemporaryAccessStoragePort {

    private final Database database;

    public SqlTemporaryAccessAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void save(TemporaryAccessGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");

        String insertGrantSql = """
                INSERT INTO temporary_access_grants (
                    grant_id, instance_id, target_root_type_id, target_root_key,
                    grantee_profile_id, granted_by_profile_id, termination_policy,
                    anchor_player_uuid, anchor_session_epoch, anchor_node_id,
                    anchor_process_generation_id, state, created_at, expires_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String insertPermSql = """
                INSERT INTO temporary_access_grant_permissions (grant_id, permission_key)
                VALUES (?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(insertGrantSql)) {
                    stmt.setString(1, grant.grantId().value().toString());
                    stmt.setString(2, grant.instanceId());
                    stmt.setString(3, grant.targetRootTypeId());
                    stmt.setString(4, grant.targetRootKey());
                    stmt.setString(5, grant.granteeProfileId().value().toString());
                    stmt.setString(6, grant.grantedByProfileId().value().toString());
                    stmt.setString(7, grant.terminationPolicy().name());

                    if (grant.anchorPlayerUuid() != null) {
                        stmt.setString(8, grant.anchorPlayerUuid().value().toString());
                    } else {
                        stmt.setNull(8, Types.VARCHAR);
                    }

                    if (grant.anchorSessionEpoch() != null) {
                        stmt.setLong(9, grant.anchorSessionEpoch());
                    } else {
                        stmt.setNull(9, Types.BIGINT);
                    }

                    if (grant.anchorNodeId() != null) {
                        stmt.setString(10, grant.anchorNodeId());
                    } else {
                        stmt.setNull(10, Types.VARCHAR);
                    }

                    if (grant.anchorProcessGenerationId() != null) {
                        stmt.setString(11, grant.anchorProcessGenerationId());
                    } else {
                        stmt.setNull(11, Types.VARCHAR);
                    }

                    stmt.setString(12, grant.state().name());
                    stmt.setTimestamp(13, Timestamp.from(grant.createdAt()));

                    if (grant.expiresAt() != null) {
                        stmt.setTimestamp(14, Timestamp.from(grant.expiresAt()));
                    } else {
                        stmt.setNull(14, Types.TIMESTAMP);
                    }

                    stmt.setTimestamp(15, Timestamp.from(grant.updatedAt()));
                    stmt.executeUpdate();
                }

                if (!grant.permissions().isEmpty()) {
                    try (PreparedStatement permStmt = conn.prepareStatement(insertPermSql)) {
                        for (PermissionKey key : grant.permissions()) {
                            permStmt.setString(1, grant.grantId().value().toString());
                            permStmt.setString(2, key.qualifiedName());
                            permStmt.addBatch();
                        }
                        permStmt.executeBatch();
                    }
                }

                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw new TemporaryAccessPersistenceException(
                        "Failed to save temporary access grant: " + grant.grantId(), ex);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new TemporaryAccessPersistenceException("Failed to obtain connection for grant save", e);
        }
    }

    @Override
    public Optional<TemporaryAccessGrant> findById(GrantId grantId) {
        Objects.requireNonNull(grantId, "grantId must not be null");

        String sql = """
                SELECT grant_id, instance_id, target_root_type_id, target_root_key,
                       grantee_profile_id, granted_by_profile_id, termination_policy,
                       anchor_player_uuid, anchor_session_epoch, anchor_node_id,
                       anchor_process_generation_id, state, created_at, expires_at, updated_at
                FROM temporary_access_grants
                WHERE grant_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, grantId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Set<PermissionKey> permissions =
                        loadPermissions(conn, grantId.value().toString());
                return Optional.of(mapRow(rs, permissions));
            }
        } catch (SQLException e) {
            throw new TemporaryAccessPersistenceException(
                    "Failed to query temporary access grant by id: " + grantId, e);
        }
    }

    @Override
    public List<TemporaryAccessGrant> findActiveByGrantee(ProfileId granteeProfileId) {
        Objects.requireNonNull(granteeProfileId, "granteeProfileId must not be null");

        String sql = """
                SELECT grant_id, instance_id, target_root_type_id, target_root_key,
                       grantee_profile_id, granted_by_profile_id, termination_policy,
                       anchor_player_uuid, anchor_session_epoch, anchor_node_id,
                       anchor_process_generation_id, state, created_at, expires_at, updated_at
                FROM temporary_access_grants
                WHERE grantee_profile_id = ? AND state = 'ACTIVE'
                """;

        try (Connection conn = database.connection()) {
            Map<String, Set<PermissionKey>> permissions = permissionsOfGrants(
                    conn,
                    "g.grantee_profile_id = ? AND g.state = 'ACTIVE'",
                    granteeProfileId.value().toString());
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, granteeProfileId.value().toString());
                return mapRows(stmt, permissions);
            }
        } catch (SQLException e) {
            throw new TemporaryAccessPersistenceException(
                    "Failed to query active grants for grantee: " + granteeProfileId, e);
        }
    }

    @Override
    public List<TemporaryAccessGrant> findActiveByRoot(String targetRootTypeId, String targetRootKey) {
        Objects.requireNonNull(targetRootTypeId, "targetRootTypeId must not be null");
        Objects.requireNonNull(targetRootKey, "targetRootKey must not be null");

        String sql = """
                SELECT grant_id, instance_id, target_root_type_id, target_root_key,
                       grantee_profile_id, granted_by_profile_id, termination_policy,
                       anchor_player_uuid, anchor_session_epoch, anchor_node_id,
                       anchor_process_generation_id, state, created_at, expires_at, updated_at
                FROM temporary_access_grants
                WHERE target_root_type_id = ? AND target_root_key = ? AND state = 'ACTIVE'
                """;

        try (Connection conn = database.connection()) {
            Map<String, Set<PermissionKey>> permissions = permissionsOfGrants(
                    conn,
                    "g.target_root_type_id = ? AND g.target_root_key = ? AND g.state = 'ACTIVE'",
                    targetRootTypeId,
                    targetRootKey);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, targetRootTypeId);
                stmt.setString(2, targetRootKey);
                return mapRows(stmt, permissions);
            }
        } catch (SQLException e) {
            throw new TemporaryAccessPersistenceException(
                    "Failed to query active grants for root: " + targetRootKey, e);
        }
    }

    @Override
    public void updateState(GrantId grantId, GrantState newState, Instant updatedAt) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        String sql = """
                UPDATE temporary_access_grants
                SET state = ?, updated_at = ?
                WHERE grant_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newState.name());
            stmt.setTimestamp(2, Timestamp.from(updatedAt));
            stmt.setString(3, grantId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TemporaryAccessPersistenceException("Failed to update state for grant: " + grantId, e);
        }
    }

    @Override
    public void purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        String sql = """
                UPDATE temporary_access_grants
                SET state = 'EXPIRED', updated_at = ?
                WHERE state = 'ACTIVE' AND termination_policy = 'UNTIL_TIMESTAMP' AND expires_at <= ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            Timestamp ts = Timestamp.from(now);
            stmt.setTimestamp(1, ts);
            stmt.setTimestamp(2, ts);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new TemporaryAccessPersistenceException("Failed to purge expired grants at: " + now, e);
        }
    }

    /**
     * The permissions of every grant {@code where} selects, read in one query.
     *
     * <p>A list of grants used to ask for each grant's permissions on its own, one query per grant,
     * and the protection path reads the grants on an island whenever its cache runs out. The
     * permissions are read before the grants, so a grant made between the two reads is read with
     * none and grants nothing until the next read. It never reads the other way round.
     */
    private static Map<String, Set<PermissionKey>> permissionsOfGrants(Connection conn, String where, String... args)
            throws SQLException {
        String sql = "SELECT p.grant_id, p.permission_key FROM temporary_access_grant_permissions p"
                + " JOIN temporary_access_grants g ON g.grant_id = p.grant_id WHERE " + where;
        Map<String, Set<PermissionKey>> byGrant = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                stmt.setString(i + 1, args[i]);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byGrant.computeIfAbsent(rs.getString("grant_id"), k -> new HashSet<>())
                            .add(PermissionKey.of(rs.getString("permission_key")));
                }
            }
        }
        return byGrant;
    }

    private List<TemporaryAccessGrant> mapRows(PreparedStatement stmt, Map<String, Set<PermissionKey>> permissions)
            throws SQLException {
        List<TemporaryAccessGrant> results = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs, permissions.getOrDefault(rs.getString("grant_id"), Set.of())));
            }
        }
        return results;
    }

    private Set<PermissionKey> loadPermissions(Connection conn, String grantId) throws SQLException {
        String sql = """
                SELECT permission_key
                FROM temporary_access_grant_permissions
                WHERE grant_id = ?
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, grantId);
            try (ResultSet rs = stmt.executeQuery()) {
                Set<PermissionKey> keys = new HashSet<>();
                while (rs.next()) {
                    keys.add(PermissionKey.of(rs.getString("permission_key")));
                }
                return keys;
            }
        }
    }

    private TemporaryAccessGrant mapRow(ResultSet rs, Set<PermissionKey> permissions) throws SQLException {
        GrantId grantId = GrantId.fromString(rs.getString("grant_id"));
        String instanceId = rs.getString("instance_id");
        String targetRootTypeId = rs.getString("target_root_type_id");
        String targetRootKey = rs.getString("target_root_key");
        ProfileId granteeProfileId = ProfileId.fromString(rs.getString("grantee_profile_id"));
        ProfileId grantedByProfileId = ProfileId.fromString(rs.getString("granted_by_profile_id"));
        TerminationPolicy policy = TerminationPolicy.valueOf(rs.getString("termination_policy"));

        String anchorPlayerRaw = rs.getString("anchor_player_uuid");
        PlayerUuid anchorPlayerUuid = anchorPlayerRaw != null ? PlayerUuid.fromString(anchorPlayerRaw) : null;

        long epochRaw = rs.getLong("anchor_session_epoch");
        Long anchorSessionEpoch = rs.wasNull() ? null : epochRaw;

        String anchorNodeId = rs.getString("anchor_node_id");
        String anchorProcessGen = rs.getString("anchor_process_generation_id");
        GrantState state = GrantState.valueOf(rs.getString("state"));

        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Timestamp expTs = rs.getTimestamp("expires_at");
        Instant expiresAt = expTs != null ? expTs.toInstant() : null;
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

        return new TemporaryAccessGrant(
                grantId,
                instanceId,
                targetRootTypeId,
                targetRootKey,
                granteeProfileId,
                grantedByProfileId,
                policy,
                anchorPlayerUuid,
                anchorSessionEpoch,
                anchorNodeId,
                anchorProcessGen,
                state,
                permissions,
                createdAt,
                expiresAt,
                updatedAt);
    }
}
