package com.uxplima.uxmskyblock.persistence.reward;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.reward.RewardStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentOperationId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentState;
import com.uxplima.uxmskyblock.core.domain.reward.RewardComponentType;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrant;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantComponent;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantId;
import com.uxplima.uxmskyblock.core.domain.reward.RewardGrantState;
import org.jspecify.annotations.Nullable;

/**
 * SQL persistence adapter for {@link RewardStoragePort} implementing durable relational storage
 * of offline reward grants and multi-protocol delivery components across SQLite, MySQL/MariaDB, and PostgreSQL.
 */
public final class SqlRewardStorageAdapter implements RewardStoragePort {

    private final Database database;

    public SqlRewardStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void saveGrant(RewardGrant grant) {
        Objects.requireNonNull(grant, "grant must not be null");

        String insertGrantSql = """
                INSERT INTO reward_grants (
                    grant_id, recipient_profile_id, source_type, source_id, state,
                    claimed_at, expires_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String insertCompSql = """
                INSERT INTO reward_grant_components (
                    component_id, grant_id, component_index, component_operation_id,
                    component_type, payload_type_id, payload_schema_version, payload_data,
                    state, journal_operation_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement grantStmt = conn.prepareStatement(insertGrantSql)) {
                    grantStmt.setString(1, grant.grantId().value().toString());
                    grantStmt.setString(2, grant.recipientProfileId().value().toString());
                    grantStmt.setString(3, grant.sourceType());
                    grantStmt.setString(4, grant.sourceId());
                    grantStmt.setString(5, grant.state().name());
                    if (grant.claimedAt() != null) {
                        grantStmt.setTimestamp(6, Timestamp.from(grant.claimedAt()));
                    } else {
                        grantStmt.setNull(6, Types.TIMESTAMP);
                    }
                    if (grant.expiresAt() != null) {
                        grantStmt.setTimestamp(7, Timestamp.from(grant.expiresAt()));
                    } else {
                        grantStmt.setNull(7, Types.TIMESTAMP);
                    }
                    grantStmt.setTimestamp(8, Timestamp.from(grant.createdAt()));
                    grantStmt.setTimestamp(9, Timestamp.from(grant.updatedAt()));
                    grantStmt.executeUpdate();
                }

                try (PreparedStatement compStmt = conn.prepareStatement(insertCompSql)) {
                    for (RewardGrantComponent comp : grant.components()) {
                        compStmt.setString(1, comp.componentId().toString());
                        compStmt.setString(2, grant.grantId().value().toString());
                        compStmt.setInt(3, comp.componentIndex());
                        compStmt.setString(
                                4, comp.componentOperationId().value().toString());
                        compStmt.setString(5, comp.componentType().name());
                        compStmt.setString(6, comp.payloadTypeId());
                        compStmt.setInt(7, comp.payloadSchemaVersion());
                        compStmt.setString(8, comp.payloadData());
                        compStmt.setString(9, comp.state().name());
                        if (comp.journalOperationId() != null) {
                            compStmt.setString(10, comp.journalOperationId().toString());
                        } else {
                            compStmt.setNull(10, Types.VARCHAR);
                        }
                        compStmt.setTimestamp(11, Timestamp.from(comp.updatedAt()));
                        compStmt.addBatch();
                    }
                    compStmt.executeBatch();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw new RewardPersistenceException("Failed to persist reward grant aggregate: " + grant.grantId(), e);
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new RewardPersistenceException("Database error while saving reward grant: " + grant.grantId(), e);
        }
    }

    @Override
    public Optional<RewardGrant> findGrantById(RewardGrantId grantId) {
        Objects.requireNonNull(grantId, "grantId must not be null");

        String grantSql = """
                SELECT grant_id, recipient_profile_id, source_type, source_id, state,
                       claimed_at, expires_at, created_at, updated_at
                FROM reward_grants
                WHERE grant_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(grantSql)) {
            stmt.setString(1, grantId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                List<RewardGrantComponent> components =
                        loadComponents(conn, grantId.value().toString());
                return Optional.of(mapGrant(rs, components));
            }
        } catch (SQLException e) {
            throw new RewardPersistenceException("Failed to query reward grant by id: " + grantId, e);
        }
    }

    @Override
    public List<RewardGrant> findPendingGrantsByRecipient(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");

        String sql = """
                SELECT grant_id, recipient_profile_id, source_type, source_id, state,
                       claimed_at, expires_at, created_at, updated_at
                FROM reward_grants
                WHERE recipient_profile_id = ?
                  AND state IN ('PENDING', 'CLAIMING', 'RECOVERY_REQUIRED')
                ORDER BY created_at ASC, grant_id ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, recipientProfileId.value().toString());
            return withComponents(conn, stmt, recipientProfileId);
        } catch (SQLException e) {
            throw new RewardPersistenceException(
                    "Failed to query pending reward grants for recipient: " + recipientProfileId, e);
        }
    }

    @Override
    public List<RewardGrant> findAllGrantsByRecipient(ProfileId recipientProfileId) {
        Objects.requireNonNull(recipientProfileId, "recipientProfileId must not be null");

        String sql = """
                SELECT grant_id, recipient_profile_id, source_type, source_id, state,
                       claimed_at, expires_at, created_at, updated_at
                FROM reward_grants
                WHERE recipient_profile_id = ?
                ORDER BY created_at ASC, grant_id ASC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, recipientProfileId.value().toString());
            return withComponents(conn, stmt, recipientProfileId);
        } catch (SQLException e) {
            throw new RewardPersistenceException(
                    "Failed to query all reward grants for recipient: " + recipientProfileId, e);
        }
    }

    @Override
    public boolean compareAndSetGrantState(
            RewardGrantId grantId,
            RewardGrantState expectedState,
            RewardGrantState newState,
            @Nullable Instant claimedAt,
            Instant updatedAt) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(expectedState, "expectedState must not be null");
        Objects.requireNonNull(newState, "newState must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        // The state in the WHERE clause is the whole guarantee. A row count of one means this claim
        // won and may hand out what the grant holds; zero means another claim already did.
        String sql = """
                UPDATE reward_grants
                SET state = ?, claimed_at = ?, updated_at = ?
                WHERE grant_id = ? AND state = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, newState.name());
            if (claimedAt != null) {
                stmt.setTimestamp(2, Timestamp.from(claimedAt));
            } else {
                stmt.setNull(2, Types.TIMESTAMP);
            }
            stmt.setTimestamp(3, Timestamp.from(updatedAt));
            stmt.setString(4, grantId.value().toString());
            stmt.setString(5, expectedState.name());
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RewardPersistenceException("Failed to move reward grant state: " + grantId, e);
        }
    }

    @Override
    public void updateGrantState(
            RewardGrantId grantId, RewardGrantState state, @Nullable Instant claimedAt, Instant updatedAt) {
        Objects.requireNonNull(grantId, "grantId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        String sql = """
                UPDATE reward_grants
                SET state = ?, claimed_at = ?, updated_at = ?
                WHERE grant_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, state.name());
            if (claimedAt != null) {
                stmt.setTimestamp(2, Timestamp.from(claimedAt));
            } else {
                stmt.setNull(2, Types.TIMESTAMP);
            }
            stmt.setTimestamp(3, Timestamp.from(updatedAt));
            stmt.setString(4, grantId.value().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RewardPersistenceException("Failed to update reward grant state: " + grantId, e);
        }
    }

    @Override
    public void updateComponentState(
            UUID componentId, RewardComponentState state, @Nullable UUID journalOperationId, Instant updatedAt) {
        Objects.requireNonNull(componentId, "componentId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        String sql = """
                UPDATE reward_grant_components
                SET state = ?, journal_operation_id = ?, updated_at = ?
                WHERE component_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, state.name());
            if (journalOperationId != null) {
                stmt.setString(2, journalOperationId.toString());
            } else {
                stmt.setNull(2, Types.VARCHAR);
            }
            stmt.setTimestamp(3, Timestamp.from(updatedAt));
            stmt.setString(4, componentId.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RewardPersistenceException("Failed to update reward grant component state: " + componentId, e);
        }
    }

    @Override
    public int expireGrants(Instant now) {
        Objects.requireNonNull(now, "now must not be null");

        String sql = """
                UPDATE reward_grants
                SET state = 'EXPIRED', updated_at = ?
                WHERE state = 'PENDING'
                  AND expires_at IS NOT NULL
                  AND expires_at <= ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            stmt.setTimestamp(2, Timestamp.from(now));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RewardPersistenceException("Failed to expire pending reward grants", e);
        }
    }

    /**
     * The grants {@code grants} selects, each with its components, in two queries.
     *
     * <p>Each grant used to ask for its own components, one query per grant, every time a player
     * opened their inbox or claimed from it. The grants are read first and their components after, so
     * every grant read has its components: a grant and its components are written in one transaction.
     * Read the other way round, a grant written between the two reads would carry no components, and
     * a claim of a grant with no components completes and hands out nothing.
     */
    private List<RewardGrant> withComponents(Connection conn, PreparedStatement grants, ProfileId recipient)
            throws SQLException {
        List<RewardGrant> read = new ArrayList<>();
        try (ResultSet rs = grants.executeQuery()) {
            while (rs.next()) {
                read.add(mapGrant(rs, List.of()));
            }
        }
        if (read.isEmpty()) {
            return read;
        }

        Map<RewardGrantId, List<RewardGrantComponent>> byGrant = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement("""
                SELECT c.component_id, c.grant_id, c.component_index, c.component_operation_id,
                       c.component_type, c.payload_type_id, c.payload_schema_version, c.payload_data,
                       c.state, c.journal_operation_id, c.updated_at
                FROM reward_grant_components c
                JOIN reward_grants g ON g.grant_id = c.grant_id
                WHERE g.recipient_profile_id = ?
                ORDER BY c.component_index ASC
                """)) {
            stmt.setString(1, recipient.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    RewardGrantComponent component = mapComponent(rs);
                    byGrant.computeIfAbsent(component.grantId(), k -> new ArrayList<>())
                            .add(component);
                }
            }
        }

        List<RewardGrant> complete = new ArrayList<>(read.size());
        for (RewardGrant grant : read) {
            complete.add(new RewardGrant(
                    grant.grantId(),
                    grant.recipientProfileId(),
                    grant.sourceType(),
                    grant.sourceId(),
                    grant.state(),
                    byGrant.getOrDefault(grant.grantId(), List.of()),
                    grant.claimedAt(),
                    grant.expiresAt(),
                    grant.createdAt(),
                    grant.updatedAt()));
        }
        return complete;
    }

    private List<RewardGrantComponent> loadComponents(Connection conn, String grantIdStr) throws SQLException {
        String sql = """
                SELECT component_id, grant_id, component_index, component_operation_id,
                       component_type, payload_type_id, payload_schema_version, payload_data,
                       state, journal_operation_id, updated_at
                FROM reward_grant_components
                WHERE grant_id = ?
                ORDER BY component_index ASC
                """;

        List<RewardGrantComponent> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, grantIdStr);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapComponent(rs));
                }
            }
        }
        return list;
    }

    private static RewardGrantComponent mapComponent(ResultSet rs) throws SQLException {
        UUID compId = UUID.fromString(rs.getString("component_id"));
        RewardGrantId grantId = RewardGrantId.fromString(rs.getString("grant_id"));
        int index = rs.getInt("component_index");
        RewardComponentOperationId opId = RewardComponentOperationId.fromString(rs.getString("component_operation_id"));
        RewardComponentType type = RewardComponentType.valueOf(rs.getString("component_type"));
        String payloadTypeId = rs.getString("payload_type_id");
        int schemaVersion = rs.getInt("payload_schema_version");
        String payloadData = rs.getString("payload_data");
        RewardComponentState state = RewardComponentState.valueOf(rs.getString("state"));
        String journalOpStr = rs.getString("journal_operation_id");
        UUID journalOp = journalOpStr != null ? UUID.fromString(journalOpStr) : null;
        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : Instant.now();

        return new RewardGrantComponent(
                compId,
                grantId,
                index,
                opId,
                type,
                payloadTypeId,
                schemaVersion,
                payloadData,
                state,
                journalOp,
                updatedAt);
    }

    private RewardGrant mapGrant(ResultSet rs, List<RewardGrantComponent> components) throws SQLException {
        RewardGrantId grantId = RewardGrantId.fromString(rs.getString("grant_id"));
        ProfileId recipientId = ProfileId.fromString(rs.getString("recipient_profile_id"));
        String sourceType = rs.getString("source_type");
        String sourceId = rs.getString("source_id");
        RewardGrantState state = RewardGrantState.valueOf(rs.getString("state"));

        Timestamp claimedTs = rs.getTimestamp("claimed_at");
        Instant claimedAt = claimedTs != null ? claimedTs.toInstant() : null;

        Timestamp expiresTs = rs.getTimestamp("expires_at");
        Instant expiresAt = expiresTs != null ? expiresTs.toInstant() : null;

        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : Instant.now();

        Timestamp updatedTs = rs.getTimestamp("updated_at");
        Instant updatedAt = updatedTs != null ? updatedTs.toInstant() : Instant.now();

        return new RewardGrant(
                grantId,
                recipientId,
                sourceType,
                sourceId,
                state,
                components,
                claimedAt,
                expiresAt,
                createdAt,
                updatedAt);
    }
}
