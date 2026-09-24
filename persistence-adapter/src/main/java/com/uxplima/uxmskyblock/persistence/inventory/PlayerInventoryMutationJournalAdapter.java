package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
    private final InventoryMutationJournalSql sql;
    private final JournalTransaction transaction;
    private final SessionAuthorityGate authority;
    private final OpenIntentSettlement settlement;
    private final JournalRecords records;

    public PlayerInventoryMutationJournalAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        this.sql = InventoryMutationJournalSql.forDialect(this.dialect);
        this.authority = new SessionAuthorityGate(sql.selectSessionAuthority());
        this.transaction = new JournalTransaction(database);
        this.settlement = new OpenIntentSettlement(database, sql, transaction, authority);
        this.records = new JournalRecords(database, sql);
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

        return transaction.run("recordIntent", conn -> {
            // 0. An aborted attempt left nothing behind it, so the operation may be tried again. Nothing
            // else ever could: the operation id is the reward's own, and a delivery aborted once, by a
            // refused commit or by recovery after a crash, stayed in the inbox for good. A refusal
            // further down rolls this back with everything else.
            forgetAborted(conn, operationId);

            // 1. Check existing journal header for idempotency / conflict
            try (PreparedStatement ps = conn.prepareStatement(sql.selectJournalForUpdate())) {
                ps.setString(1, operationId.value().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String existingOpType = rs.getString("operation_type");
                        String existingState = rs.getString("state");
                        String existingPayload = rs.getString("payload");

                        // Check participant 0
                        try (PreparedStatement partPs = conn.prepareStatement(sql.selectParticipantForUpdate())) {
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
            Optional<String> refusal = authority.refusal(conn, playerUuid, profileId, nodeId, sessionEpoch, true);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }

            // 3. Verify current inventory version matches expected
            try (PreparedStatement ps = conn.prepareStatement(sql.selectInventoryVersion())) {
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
            try (PreparedStatement ps = conn.prepareStatement(sql.insertJournal())) {
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
            try (PreparedStatement ps = conn.prepareStatement(sql.insertParticipant())) {
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

    private void forgetAborted(Connection conn, InventoryMutationOperationId operationId) throws SQLException {
        String id = operationId.value().toString();
        try (PreparedStatement ps = conn.prepareStatement(sql.deleteAbortedParticipants())) {
            ps.setString(1, id);
            ps.setString(2, id);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.deleteAbortedJournal())) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
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

        return transaction.run("commitMutation", conn -> {
            // 1. Verify journal exists and check state
            String opType;
            String state;
            try (PreparedStatement ps = conn.prepareStatement(sql.selectJournalForUpdate())) {
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
                try (PreparedStatement ps = conn.prepareStatement(sql.selectParticipantForUpdate())) {
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
            try (PreparedStatement ps = conn.prepareStatement(sql.selectParticipantForUpdate())) {
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
            Optional<String> refusal = authority.refusal(conn, playerUuid, profileId, nodeId, sessionEpoch, true);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }

            // 4. Update profile_inventories OCC (version -> version + 1)
            try (PreparedStatement ps = conn.prepareStatement(sql.updateInventoryOcc())) {
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
            try (PreparedStatement ps = conn.prepareStatement(sql.updateSessionLastDurableVersion())) {
                ps.setLong(1, newVersion);
                ps.setString(2, playerUuid.value().toString());
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("SESSION_UPDATE_FAILED");
                }
            }

            // 6. Update participant apply state to APPLIED
            try (PreparedStatement ps = conn.prepareStatement(sql.updateParticipantState())) {
                ps.setString(1, "APPLIED");
                ps.setString(2, operationId.value().toString());
                ps.setInt(3, 0);
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("PARTICIPANT_UPDATE_FAILED");
                }
            }

            // 7. Update journal state to COMMITTED
            try (PreparedStatement ps = conn.prepareStatement(sql.updateJournalState())) {
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

        return transaction.run("abortIntent", conn -> {
            // 1. Check journal state
            String state;
            try (PreparedStatement ps = conn.prepareStatement(sql.selectJournalForUpdate())) {
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
            Optional<String> refusal = authority.refusal(conn, playerUuid, profileId, nodeId, sessionEpoch, false);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }

            // 3. Mark journal ABORTED
            try (PreparedStatement ps = conn.prepareStatement(sql.updateJournalState())) {
                ps.setString(1, "ABORTED");
                ps.setString(2, operationId.value().toString());
                int updated = ps.executeUpdate();
                if (updated != 1) {
                    return InventoryMutationJournalOutcome.rejected("JOURNAL_UPDATE_FAILED");
                }
            }

            // 4. Mark participant REVERTED
            try (PreparedStatement ps = conn.prepareStatement(sql.updateParticipantState())) {
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
    public List<InventoryMutationOperationId> findOpenIntents(ProfileId profileId) {
        return settlement.findOpenIntents(profileId);
    }

    @Override
    public InventoryMutationJournalOutcome settleOpenIntent(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            InventoryMutationOperationId operationId,
            InventoryMutationJournalState settledAs) {
        return settlement.settle(playerUuid, profileId, nodeId, sessionEpoch, operationId, settledAs);
    }

    @Override
    public Optional<InventoryMutationJournalRecord> loadJournal(InventoryMutationOperationId operationId) {
        return records.journal(operationId);
    }

    @Override
    public Optional<InventoryMutationParticipantRecord> loadParticipant(
            InventoryMutationOperationId operationId, int participantIndex) {
        return records.participant(operationId, participantIndex);
    }

    @Override
    public int purgeSettledBefore(Instant before) {
        java.util.Objects.requireNonNull(before, "before must not be null");

        // A journal in RECOVERY_REQUIRED is never deleted: that state means a crash left something
        // nobody has reconciled, and the row is the only record of it. The participants go with the
        // journal on their own, through the foreign key.
        String purgeSql = "DELETE FROM inventory_mutation_journals WHERE state IN (?, ?) AND updated_at < ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(purgeSql)) {
            ps.setString(1, InventoryMutationJournalState.COMMITTED.name());
            ps.setString(2, InventoryMutationJournalState.ABORTED.name());
            ps.setTimestamp(3, java.sql.Timestamp.from(before));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to purge settled inventory journals before " + before, e);
        }
    }
}
