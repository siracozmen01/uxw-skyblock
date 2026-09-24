package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Finds the intents a crash left open and settles them as recovery decides.
 *
 * <p>Settling takes the same session authority a commit does, under the same row lock, so a node
 * that lost the player cannot settle what the node holding it is about to decide.
 */
final class OpenIntentSettlement {

    private static final String FIND_OPEN = "SELECT j.operation_id FROM inventory_mutation_journals j "
            + "JOIN inventory_mutation_participants p "
            + "ON p.operation_id = j.operation_id AND p.participant_index = 0 "
            + "WHERE p.owner_root_id = ? AND j.state = 'INTENT' "
            + "ORDER BY j.created_at, j.operation_id";

    private final Database database;
    private final InventoryMutationJournalSql sql;
    private final JournalTransaction transaction;
    private final SessionAuthorityGate authority;

    OpenIntentSettlement(
            Database database,
            InventoryMutationJournalSql sql,
            JournalTransaction transaction,
            SessionAuthorityGate authority) {
        this.database = Objects.requireNonNull(database, "database");
        this.sql = Objects.requireNonNull(sql, "sql");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    List<InventoryMutationOperationId> findOpenIntents(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(FIND_OPEN)) {
            ps.setString(1, profileId.value().toString());
            List<InventoryMutationOperationId> open = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    open.add(InventoryMutationOperationId.of(rs.getString(1)));
                }
            }
            return List.copyOf(open);
        } catch (SQLException e) {
            throw new InventoryPersistenceException("Failed to find the open intents of " + profileId, e);
        }
    }

    InventoryMutationJournalOutcome settle(
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            InventoryMutationOperationId operationId,
            InventoryMutationJournalState settledAs) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(settledAs, "settledAs");
        if (settledAs != InventoryMutationJournalState.COMMITTED
                && settledAs != InventoryMutationJournalState.RECOVERY_REQUIRED) {
            throw new IllegalArgumentException(
                    "An open intent settles as COMMITTED or RECOVERY_REQUIRED, not " + settledAs);
        }
        return transaction.run("settleOpenIntent", conn -> {
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
            if (settledAs.name().equals(state)) {
                return InventoryMutationJournalOutcome.success();
            }
            if (!"INTENT".equals(state)) {
                return InventoryMutationJournalOutcome.rejected("INVALID_JOURNAL_STATE");
            }
            Optional<String> owner = ownerOf(conn, operationId);
            if (owner.isEmpty()) {
                return InventoryMutationJournalOutcome.rejected("PARTICIPANT_NOT_FOUND");
            }
            if (!owner.get().equals(profileId.value().toString())) {
                return InventoryMutationJournalOutcome.rejected("CROSS_PROFILE_MISMATCH");
            }
            Optional<String> refusal = authority.refusal(conn, playerUuid, profileId, nodeId, sessionEpoch, true);
            if (refusal.isPresent()) {
                return InventoryMutationJournalOutcome.rejected(refusal.get());
            }
            if (settledAs == InventoryMutationJournalState.COMMITTED
                    && update(conn, sql.updateParticipantState(), "APPLIED", operationId, true) != 1) {
                return InventoryMutationJournalOutcome.rejected("PARTICIPANT_UPDATE_FAILED");
            }
            if (update(conn, sql.updateJournalState(), settledAs.name(), operationId, false) != 1) {
                return InventoryMutationJournalOutcome.rejected("JOURNAL_UPDATE_FAILED");
            }
            return InventoryMutationJournalOutcome.success();
        });
    }

    private Optional<String> ownerOf(Connection conn, InventoryMutationOperationId operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql.selectParticipantForUpdate())) {
            ps.setString(1, operationId.value().toString());
            ps.setInt(2, 0);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("owner_root_id")) : Optional.empty();
            }
        }
    }

    private static int update(
            Connection conn, String statement, String state, InventoryMutationOperationId operationId, boolean indexed)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(statement)) {
            ps.setString(1, state);
            ps.setString(2, operationId.value().toString());
            if (indexed) {
                ps.setInt(3, 0);
            }
            return ps.executeUpdate();
        }
    }
}
