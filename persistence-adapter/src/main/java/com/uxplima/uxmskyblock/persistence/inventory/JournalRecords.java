package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;

/** Reads a journal and its participants as they stand, without taking a lock on either. */
final class JournalRecords {

    private final Database database;
    private final InventoryMutationJournalSql sql;

    JournalRecords(Database database, InventoryMutationJournalSql sql) {
        this.database = Objects.requireNonNull(database, "database");
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    Optional<InventoryMutationJournalRecord> journal(InventoryMutationOperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql.selectJournalRead())) {
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

    Optional<InventoryMutationParticipantRecord> participant(
            InventoryMutationOperationId operationId, int participantIndex) {
        Objects.requireNonNull(operationId, "operationId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql.selectParticipantRead())) {
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
