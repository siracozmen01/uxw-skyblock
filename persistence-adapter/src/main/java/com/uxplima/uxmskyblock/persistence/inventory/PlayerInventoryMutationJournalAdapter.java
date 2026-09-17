package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryMutationJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Canonical SQL persistence adapter implementing {@link InventoryMutationJournalPort} (WP2-005).
 *
 * <p>Governs write-ahead persistence for IMMEDIATE economic/value-sensitive Minecraft item
 * mutations under Hybrid durability. Enforces the two-phase write-ahead protocol:
 * <ol>
 *   <li><b>Phase 1 (INTENT):</b> Acquires canonical session authority lock, verifies active-profile
 *       binding, session state (strictly {@code ACTIVE}), lease validity by DB clock, and expected
 *       inventory OCC version, then durably writes header ({@code INTENT}) and participant rows.</li>
 *   <li><b>Phase 2 (Completion):</b> Under session authority row lock, verifies journal state, executes
 *       OCC update on {@code profile_inventories}, atomically synchronizes {@code player_sessions.last_durable_inventory_version},
 *       and marks the journal {@code COMMITTED}.</li>
 * </ol>
 */
public final class PlayerInventoryMutationJournalAdapter implements InventoryMutationJournalPort {

    private final Database database;
    private final Dialect dialect;

    private final String selectSessionAuthoritySql;
    private final String selectInventoryVersionSql;
    private final String selectJournalForUpdateSql;
    private final String selectParticipantForUpdateSql;
    private final String insertJournalSql;
    private final String insertParticipantSql;
    private final String updateInventoryOccSql;
    private final String updateSessionLastDurableVersionSql;
    private final String updateJournalStateSql;
    private final String updateParticipantStateSql;

    private final String selectJournalReadSql;
    private final String selectParticipantReadSql;

    public PlayerInventoryMutationJournalAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        validateDialect(this.dialect);

        this.selectSessionAuthoritySql = buildSelectSessionAuthoritySql(this.dialect);
        this.selectInventoryVersionSql = buildSelectInventoryVersionSql(this.dialect);
        this.selectJournalForUpdateSql = buildSelectJournalSql(this.dialect, true);
        this.selectParticipantForUpdateSql = buildSelectParticipantSql(this.dialect, true);

        if (dialect == Dialect.POSTGRES) {
            this.insertJournalSql = "INSERT INTO inventory_mutation_journals "
                    + "(operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at) "
                    + "VALUES (?, ?, 'INTENT', 1, CAST(? AS json), ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
            this.insertParticipantSql = "INSERT INTO inventory_mutation_participants "
                    + "(operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                    + "expected_version, authority_type, authority_id, authority_epoch, "
                    + "before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at) "
                    + "VALUES (?, 0, 'PLAYER_INVENTORY', 'PROFILE', ?, ?, 'SERVER_NODE', ?, ?, ?, ?, 'PENDING', CAST(? AS json), CURRENT_TIMESTAMP)";
        } else {
            this.insertJournalSql = "INSERT INTO inventory_mutation_journals "
                    + "(operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at) "
                    + "VALUES (?, ?, 'INTENT', 1, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
            this.insertParticipantSql = "INSERT INTO inventory_mutation_participants "
                    + "(operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                    + "expected_version, authority_type, authority_id, authority_epoch, "
                    + "before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at) "
                    + "VALUES (?, 0, 'PLAYER_INVENTORY', 'PROFILE', ?, ?, 'SERVER_NODE', ?, ?, ?, ?, 'PENDING', ?, CURRENT_TIMESTAMP)";
        }

        this.updateInventoryOccSql = "UPDATE profile_inventories "
                + "SET profile_inventory_version = profile_inventory_version + 1, "
                + "inventory_nbt = ?, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE profile_id = ? "
                + "AND profile_inventory_version = ?";

        this.updateSessionLastDurableVersionSql = "UPDATE player_sessions "
                + "SET last_durable_inventory_version = ?, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ?";

        this.updateJournalStateSql = "UPDATE inventory_mutation_journals "
                + "SET state = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ?";

        this.updateParticipantStateSql = "UPDATE inventory_mutation_participants "
                + "SET durable_apply_state = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ? AND participant_index = ?";

        this.selectJournalReadSql = buildSelectJournalSql(this.dialect, false);
        this.selectParticipantReadSql = buildSelectParticipantSql(this.dialect, false);
    }

    private static void validateDialect(Dialect dialect) {
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock inventory persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }

    private static String buildSelectSessionAuthoritySql(Dialect dialect) {
        String base = "SELECT active_profile_id, authoritative_node, session_epoch, state, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM player_sessions "
                + "WHERE player_uuid = ?";
        return dialect == Dialect.SQLITE ? base : base + " FOR UPDATE";
    }

    private static String buildSelectInventoryVersionSql(Dialect dialect) {
        String base = "SELECT profile_inventory_version FROM profile_inventories WHERE profile_id = ?";
        return dialect == Dialect.SQLITE ? base : base + " FOR UPDATE";
    }

    private static String buildSelectJournalSql(Dialect dialect, boolean forUpdate) {
        String base =
                "SELECT operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at "
                        + "FROM inventory_mutation_journals WHERE operation_id = ?";
        return (forUpdate && dialect != Dialect.SQLITE) ? base + " FOR UPDATE" : base;
    }

    private static String buildSelectParticipantSql(Dialect dialect, boolean forUpdate) {
        String base = "SELECT operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                + "expected_version, authority_type, authority_id, authority_epoch, before_fingerprint, "
                + "after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at "
                + "FROM inventory_mutation_participants WHERE operation_id = ? AND participant_index = ?";
        return (forUpdate && dialect != Dialect.SQLITE) ? base + " FOR UPDATE" : base;
    }

    @FunctionalInterface
    private interface TxAction<T> {
        T execute(Connection conn) throws SQLException;
    }

    private InventoryMutationJournalOutcome executeTx(
            String opDescription, TxAction<InventoryMutationJournalOutcome> action) {
        if (dialect == Dialect.SQLITE) {
            try (Connection conn = database.connection()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("BEGIN IMMEDIATE");
                }
                try {
                    InventoryMutationJournalOutcome outcome = action.execute(conn);
                    if (outcome.isSuccess()) {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("COMMIT");
                        }
                    } else {
                        try (Statement stmt = conn.createStatement()) {
                            stmt.execute("ROLLBACK");
                        }
                    }
                    return outcome;
                } catch (Exception e) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ROLLBACK");
                    } catch (SQLException rollbackEx) {
                        e.addSuppressed(rollbackEx);
                    }
                    throw e;
                }
            } catch (SQLException e) {
                throw new InventoryPersistenceException("Failed SQLite " + opDescription, e);
            }
        } else {
            try (Connection conn = database.connection()) {
                conn.setAutoCommit(false);
                try {
                    InventoryMutationJournalOutcome outcome = action.execute(conn);
                    if (outcome.isSuccess()) {
                        conn.commit();
                    } else {
                        conn.rollback();
                    }
                    return outcome;
                } catch (Exception e) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackEx) {
                        e.addSuppressed(rollbackEx);
                    }
                    throw e;
                }
            } catch (SQLException e) {
                throw new InventoryPersistenceException("Failed server DB " + opDescription, e);
            }
        }
    }

    @Override
    public InventoryMutationJournalOutcome recordIntent(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            long expectedVersion,
            InventoryMutationOperationId operationId,
            String operationType,
            String beforeFingerprint,
            String afterFingerprint,
            String payload,
            Duration expiryDuration) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(operationType, "operationType");
        Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
        Objects.requireNonNull(afterFingerprint, "afterFingerprint");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expiryDuration, "expiryDuration");

        return executeTx("recordIntent", conn -> {
            // 1. Check existing journal header for idempotency / conflict
            try (PreparedStatement ps = conn.prepareStatement(selectJournalForUpdateSql)) {
                ps.setString(1, operationId.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String existingOpType = rs.getString("operation_type");
                        String existingState = rs.getString("state");
                        String existingPayload = rs.getString("payload");

                        // Check participant 0
                        try (PreparedStatement partPs = conn.prepareStatement(selectParticipantForUpdateSql)) {
                            partPs.setString(1, operationId.value().toString());
                            partPs.setInt(2, 0);
                            try (ResultSet partRs = partPs.executeQuery()) {
                                if (partRs.next()) {
                                    String existingOwnerId = partRs.getString("owner_root_id");
                                    long existingExpVer = partRs.getLong("expected_version");
                                    String existingBeforeFp = partRs.getString("before_fingerprint");
                                    String existingAfterFp = partRs.getString("after_fingerprint");

                                    boolean matching = existingOpType.equals(operationType)
                                            && existingOwnerId.equals(
                                                    profileId.value().toString())
                                            && existingExpVer == expectedVersion
                                            && existingBeforeFp.equals(beforeFingerprint)
                                            && existingAfterFp.equals(afterFingerprint)
                                            && existingPayload.equals(payload);

                                    if (matching) {
                                        if ("INTENT".equals(existingState)) {
                                            return InventoryMutationJournalOutcome.success(expectedVersion);
                                        } else if ("COMMITTED".equals(existingState)) {
                                            return InventoryMutationJournalOutcome.success(expectedVersion + 1);
                                        } else if ("ABORTED".equals(existingState)) {
                                            return InventoryMutationJournalOutcome.rejected("OPERATION_ABORTED");
                                        }
                                    }
                                }
                            }
                        }
                        return InventoryMutationJournalOutcome.conflict("OPERATION_CONFLICT");
                    }
                }
            }

            // 2. Validate authority under canonical row lock
            try (PreparedStatement ps = conn.prepareStatement(selectSessionAuthoritySql)) {
                ps.setString(1, playerUuid.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("SESSION_NOT_FOUND");
                    }
                    String activeProfileId = rs.getString("active_profile_id");
                    String authoritativeNode = rs.getString("authoritative_node");
                    long epoch = rs.getLong("session_epoch");
                    String state = rs.getString("state");
                    int leaseValid = rs.getInt("lease_valid");

                    if (!profileId.value().toString().equals(activeProfileId)) {
                        return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
                    }
                    if (!nodeId.value().equals(authoritativeNode)) {
                        return InventoryMutationJournalOutcome.rejected("WRONG_NODE");
                    }
                    if (sessionEpoch != epoch) {
                        return InventoryMutationJournalOutcome.rejected("STALE_EPOCH");
                    }
                    if (!"ACTIVE".equals(state)) {
                        return InventoryMutationJournalOutcome.rejected("SESSION_NOT_ACTIVE");
                    }
                    if (leaseValid != 1) {
                        return InventoryMutationJournalOutcome.rejected("LEASE_EXPIRED");
                    }
                }
            }

            // 3. Verify current inventory version matches expected
            try (PreparedStatement ps = conn.prepareStatement(selectInventoryVersionSql)) {
                ps.setString(1, profileId.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("PROFILE_INVENTORY_NOT_FOUND");
                    }
                    long currentVer = rs.getLong("profile_inventory_version");
                    if (currentVer != expectedVersion) {
                        return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                    }
                }
            }

            // 4. Insert journal header
            Timestamp expiresAt = new Timestamp(System.currentTimeMillis() + expiryDuration.toMillis());
            try (PreparedStatement ps = conn.prepareStatement(insertJournalSql)) {
                ps.setString(1, operationId.value().toString());
                ps.setString(2, operationType);
                ps.setString(3, payload);
                ps.setTimestamp(4, expiresAt);
                int inserted = ps.executeUpdate();
                if (inserted != 1) {
                    return InventoryMutationJournalOutcome.rejected("JOURNAL_INSERT_FAILED");
                }
            }

            // 5. Insert participant record
            try (PreparedStatement ps = conn.prepareStatement(insertParticipantSql)) {
                ps.setString(1, operationId.value().toString());
                ps.setString(2, profileId.value().toString());
                ps.setLong(3, expectedVersion);
                ps.setString(4, nodeId.value());
                ps.setLong(5, sessionEpoch);
                ps.setString(6, beforeFingerprint);
                ps.setString(7, afterFingerprint);
                ps.setString(8, payload);
                int inserted = ps.executeUpdate();
                if (inserted != 1) {
                    return InventoryMutationJournalOutcome.rejected("PARTICIPANT_INSERT_FAILED");
                }
            }

            return InventoryMutationJournalOutcome.success(expectedVersion);
        });
    }

    @Override
    public InventoryMutationJournalOutcome commitMutation(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            long expectedVersion,
            InventoryMutationOperationId operationId,
            byte[] updatedInventoryNbt) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(updatedInventoryNbt, "updatedInventoryNbt");

        return executeTx("commitMutation", conn -> {
            // 1. Verify journal exists and check state
            String opType;
            String state;
            try (PreparedStatement ps = conn.prepareStatement(selectJournalForUpdateSql)) {
                ps.setString(1, operationId.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("JOURNAL_NOT_FOUND");
                    }
                    opType = rs.getString("operation_type");
                    state = rs.getString("state");
                }
            }

            if ("ABORTED".equals(state)) {
                return InventoryMutationJournalOutcome.rejected("JOURNAL_ABORTED");
            }

            // If already committed, verify participant and return idempotent success
            if ("COMMITTED".equals(state)) {
                try (PreparedStatement ps = conn.prepareStatement(selectParticipantForUpdateSql)) {
                    ps.setString(1, operationId.value().toString());
                    ps.setInt(2, 0);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return InventoryMutationJournalOutcome.rejected("PARTICIPANT_NOT_FOUND");
                        }
                        String ownerId = rs.getString("owner_root_id");
                        long expVer = rs.getLong("expected_version");
                        if (ownerId.equals(profileId.value().toString()) && expVer == expectedVersion) {
                            return InventoryMutationJournalOutcome.success(expectedVersion + 1);
                        }
                        return InventoryMutationJournalOutcome.conflict("PARTICIPANT_MISMATCH");
                    }
                }
            }

            if (!"INTENT".equals(state)) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }

            // 2. Verify participant
            try (PreparedStatement ps = conn.prepareStatement(selectParticipantForUpdateSql)) {
                ps.setString(1, operationId.value().toString());
                ps.setInt(2, 0);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("PARTICIPANT_NOT_FOUND");
                    }
                    String ownerId = rs.getString("owner_root_id");
                    long expVer = rs.getLong("expected_version");
                    String applyState = rs.getString("durable_apply_state");

                    if (!ownerId.equals(profileId.value().toString())) {
                        return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
                    }
                    if (expVer != expectedVersion) {
                        return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                    }
                    if (!"PENDING".equals(applyState)) {
                        return InventoryMutationJournalOutcome.rejected("INVALID_PARTICIPANT_STATE");
                    }
                }
            }

            // 3. Validate authority under canonical row lock
            try (PreparedStatement ps = conn.prepareStatement(selectSessionAuthoritySql)) {
                ps.setString(1, playerUuid.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("SESSION_NOT_FOUND");
                    }
                    String activeProfileId = rs.getString("active_profile_id");
                    String authoritativeNode = rs.getString("authoritative_node");
                    long epoch = rs.getLong("session_epoch");
                    String sessionState = rs.getString("state");
                    int leaseValid = rs.getInt("lease_valid");

                    if (!profileId.value().toString().equals(activeProfileId)) {
                        return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
                    }
                    if (!nodeId.value().equals(authoritativeNode)) {
                        return InventoryMutationJournalOutcome.rejected("WRONG_NODE");
                    }
                    if (sessionEpoch != epoch) {
                        return InventoryMutationJournalOutcome.rejected("STALE_EPOCH");
                    }
                    if (!"ACTIVE".equals(sessionState)) {
                        return InventoryMutationJournalOutcome.rejected("SESSION_NOT_ACTIVE");
                    }
                    if (leaseValid != 1) {
                        return InventoryMutationJournalOutcome.rejected("LEASE_EXPIRED");
                    }
                }
            }

            // 4. Update profile_inventories OCC (version -> version + 1)
            try (PreparedStatement ps = conn.prepareStatement(updateInventoryOccSql)) {
                ps.setBytes(1, updatedInventoryNbt);
                ps.setString(2, profileId.value().toString());
                ps.setLong(3, expectedVersion);
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
                }
            }

            long newVersion = expectedVersion + 1;

            // 5. Update player_sessions.last_durable_inventory_version
            try (PreparedStatement ps = conn.prepareStatement(updateSessionLastDurableVersionSql)) {
                ps.setLong(1, newVersion);
                ps.setString(2, playerUuid.value().toString());
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("SESSION_UPDATE_FAILED");
                }
            }

            // 6. Update participant apply state to APPLIED
            try (PreparedStatement ps = conn.prepareStatement(updateParticipantStateSql)) {
                ps.setString(1, "APPLIED");
                ps.setString(2, operationId.value().toString());
                ps.setInt(3, 0);
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("PARTICIPANT_UPDATE_FAILED");
                }
            }

            // 7. Update journal state to COMMITTED
            try (PreparedStatement ps = conn.prepareStatement(updateJournalStateSql)) {
                ps.setString(1, "COMMITTED");
                ps.setString(2, operationId.value().toString());
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("JOURNAL_UPDATE_FAILED");
                }
            }

            return InventoryMutationJournalOutcome.success(newVersion);
        });
    }

    @Override
    public InventoryMutationJournalOutcome abortIntent(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            InventoryMutationOperationId operationId) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(operationId, "operationId");

        return executeTx("abortIntent", conn -> {
            // 1. Check journal state
            String state;
            try (PreparedStatement ps = conn.prepareStatement(selectJournalForUpdateSql)) {
                ps.setString(1, operationId.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("JOURNAL_NOT_FOUND");
                    }
                    state = rs.getString("state");
                }
            }

            if ("COMMITTED".equals(state)) {
                return InventoryMutationJournalOutcome.rejected("CANNOT_ABORT_COMMITTED");
            }
            if ("ABORTED".equals(state)) {
                return InventoryMutationJournalOutcome.success();
            }
            if (!"INTENT".equals(state)) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }

            // 2. Validate authority under canonical row lock
            try (PreparedStatement ps = conn.prepareStatement(selectSessionAuthoritySql)) {
                ps.setString(1, playerUuid.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return InventoryMutationJournalOutcome.rejected("SESSION_NOT_FOUND");
                    }
                    String activeProfileId = rs.getString("active_profile_id");
                    String authoritativeNode = rs.getString("authoritative_node");
                    long epoch = rs.getLong("session_epoch");
                    int leaseValid = rs.getInt("lease_valid");

                    if (!profileId.value().toString().equals(activeProfileId)) {
                        return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
                    }
                    if (!nodeId.value().equals(authoritativeNode)) {
                        return InventoryMutationJournalOutcome.rejected("WRONG_NODE");
                    }
                    if (sessionEpoch != epoch) {
                        return InventoryMutationJournalOutcome.rejected("STALE_EPOCH");
                    }
                    if (leaseValid != 1) {
                        return InventoryMutationJournalOutcome.rejected("LEASE_EXPIRED");
                    }
                }
            }

            // 3. Mark journal ABORTED
            try (PreparedStatement ps = conn.prepareStatement(updateJournalStateSql)) {
                ps.setString(1, "ABORTED");
                ps.setString(2, operationId.value().toString());
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("JOURNAL_UPDATE_FAILED");
                }
            }

            // 4. Mark participant REVERTED
            try (PreparedStatement ps = conn.prepareStatement(updateParticipantStateSql)) {
                ps.setString(1, "REVERTED");
                ps.setString(2, operationId.value().toString());
                ps.setInt(3, 0);
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("PARTICIPANT_UPDATE_FAILED");
                }
            }

            return InventoryMutationJournalOutcome.success();
        });
    }

    @Override
    public Optional<InventoryMutationJournalRecord> loadJournal(InventoryMutationOperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(selectJournalReadSql)) {
            ps.setString(1, operationId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String opId = rs.getString("operation_id");
                String opType = rs.getString("operation_type");
                String state = rs.getString("state");
                int participantCount = rs.getInt("participant_count");
                String payload = rs.getString("payload");
                Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                Instant createdAt = rs.getTimestamp("created_at").toInstant();
                Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

                return Optional.of(new InventoryMutationJournalRecord(
                        InventoryMutationOperationId.fromString(opId),
                        opType,
                        InventoryMutationJournalState.valueOf(state),
                        participantCount,
                        payload,
                        expiresAt,
                        createdAt,
                        updatedAt));
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to load journal for " + operationId, e);
        }
    }

    @Override
    public Optional<InventoryMutationParticipantRecord> loadParticipant(
            InventoryMutationOperationId operationId, int participantIndex) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(selectParticipantReadSql)) {
            ps.setString(1, operationId.value().toString());
            ps.setInt(2, participantIndex);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String opId = rs.getString("operation_id");
                int partIndex = rs.getInt("participant_index");
                String invType = rs.getString("inventory_type");
                String ownerRootType = rs.getString("owner_root_type");
                String ownerRootId = rs.getString("owner_root_id");
                long expVer = rs.getLong("expected_version");
                String authType = rs.getString("authority_type");
                String authId = rs.getString("authority_id");
                long authEpoch = rs.getLong("authority_epoch");
                String beforeFp = rs.getString("before_fingerprint");
                String afterFp = rs.getString("after_fingerprint");
                String applyState = rs.getString("durable_apply_state");
                String deltaPayload = rs.getString("mutation_delta_payload");
                Instant updatedAt = rs.getTimestamp("updated_at").toInstant();

                return Optional.of(new InventoryMutationParticipantRecord(
                        InventoryMutationOperationId.fromString(opId),
                        partIndex,
                        invType,
                        ownerRootType,
                        ownerRootId,
                        expVer,
                        authType,
                        authId,
                        authEpoch,
                        beforeFp,
                        afterFp,
                        ParticipantApplyState.valueOf(applyState),
                        deltaPayload,
                        updatedAt));
            }
        } catch (SQLException e) {
            throw new InventoryPersistenceException(
                    "Failed to load participant for " + operationId + " index " + participantIndex, e);
        }
    }
}
