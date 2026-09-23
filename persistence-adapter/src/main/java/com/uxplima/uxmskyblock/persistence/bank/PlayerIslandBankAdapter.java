package com.uxplima.uxmskyblock.persistence.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.persistence.sql.DialectTransactions;
import com.uxplima.uxmskyblock.persistence.sql.SupportedDialects;
import com.uxplima.uxmskyblock.persistence.sql.UniqueViolations;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL persistence adapter for Island Bank operations.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Dual-mode transactions (SQLite {@code BEGIN IMMEDIATE} vs MariaDB/Postgres row-level locks)</li>
 *   <li>Authority epoch fencing via {@code island_authorities} row locks</li>
 *   <li>Atomic scoped idempotency reservation via {@code processed_operations}</li>
 *   <li>Optimistic concurrency control via monotonic version check on {@code island_banks}</li>
 *   <li>Exact minor units financial mathematics preventing negative balances</li>
 *   <li>Append-only audit trail logging into {@code bank_transactions}</li>
 * </ul>
 */
public final class PlayerIslandBankAdapter implements IslandBankPort {

    private final Database database;
    private final Dialect dialect;
    private final DialectTransactions tx;
    private final SqlIslandBankLedger ledger;

    public PlayerIslandBankAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.dialect = database.dialect();
        this.tx = new DialectTransactions(this.dialect);
        this.ledger = new SqlIslandBankLedger(database);
        SupportedDialects.require(dialect, "island bank persistence");
    }

    @Override
    public BankTransactionOutcome executeTransaction(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey) {
        return executeTransaction(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                null);
    }

    @Override
    public BankTransactionOutcome executeTransaction(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            @Nullable StagedOutboxEvent outboxEvent) {
        return executeTransaction(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                "ISLAND_BANK",
                outboxEvent);
    }

    @Override
    public BankTransactionOutcome executeTransaction(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            String operationScope,
            @Nullable StagedOutboxEvent outboxEvent) {
        return Objects.requireNonNull(transact(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                operationScope,
                outboxEvent,
                null));
    }

    /**
     * The same charge, with {@code step} written in its transaction.
     *
     * @return the outcome, or {@code null} when the charge went through and {@code step} answered
     *     false, so both were rolled back and nothing happened
     */
    public @Nullable BankTransactionOutcome executeTransactionWith(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            String operationScope,
            @Nullable StagedOutboxEvent outboxEvent,
            InTransaction step) {
        Objects.requireNonNull(step, "step");
        return transact(
                islandId,
                actorUuid,
                currencyId,
                currencyScale,
                deltaAmountMinorUnits,
                reason,
                currentNode,
                expectedEpoch,
                expectedVersion,
                operationId,
                idempotencyKey,
                operationScope,
                outboxEvent,
                step);
    }

    private @Nullable BankTransactionOutcome transact(
            IslandId islandId,
            UUID actorUuid,
            String currencyId,
            int currencyScale,
            long deltaAmountMinorUnits,
            String reason,
            String currentNode,
            long expectedEpoch,
            long expectedVersion,
            UUID operationId,
            String idempotencyKey,
            String operationScope,
            @Nullable StagedOutboxEvent outboxEvent,
            @Nullable InTransaction step) {

        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(currentNode, "currentNode");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        String effectiveScope =
                (operationScope != null && !operationScope.isBlank()) ? operationScope.trim() : "ISLAND_BANK";

        String upperCurrency = currencyId.trim().toUpperCase(Locale.ROOT);
        if (!upperCurrency.equals("PRIMARY") && !upperCurrency.equals("CRYSTALS") && !upperCurrency.equals("EXP")) {
            throw new IllegalArgumentException("Unsupported currency ID: " + currencyId);
        }

        try (Connection connection = database.connection()) {
            tx.begin(connection);
            try {
                // Step 1: Idempotency check on processed_operations
                BankTransactionOutcome.DuplicateOperation recorded =
                        recordedOperation(connection, effectiveScope, actorUuid, idempotencyKey, operationId);
                if (recorded != null) {
                    tx.rollbackQuietly(connection);
                    return recorded;
                }

                // Step 2: Insert PENDING reservation in processed_operations
                String insertOpSql = """
                        INSERT INTO processed_operations (
                            operation_id, operation_scope, actor_id, idempotency_key,
                            operation_type, resource_id, status, created_at
                        ) VALUES (?, ?, ?, ?, 'BANK_TRANSACTION', ?, 'PENDING', CURRENT_TIMESTAMP)
                        """;
                try (PreparedStatement ps = connection.prepareStatement(insertOpSql)) {
                    ps.setString(1, operationId.toString());
                    ps.setString(2, effectiveScope);
                    ps.setString(3, actorUuid.toString());
                    ps.setString(4, idempotencyKey);
                    ps.setString(5, islandId.value().toString());
                    ps.executeUpdate();
                } catch (SQLException collided) {
                    // Another request with the same scope, actor and key reserved it between the check
                    // above and this insert, and the engine held this one on the unique index until that
                    // one committed. That is the same request, not a failure: roll this draft back and
                    // answer with what the other one recorded. It used to surface as a persistence
                    // error, so a caller retrying one deposit got a server error for its own retry.
                    if (!UniqueViolations.isUniqueViolation(collided)) {
                        throw collided;
                    }
                    tx.rollbackQuietly(connection);
                    BankTransactionOutcome.DuplicateOperation winner =
                            recordedOperation(connection, effectiveScope, actorUuid, idempotencyKey, operationId);
                    tx.rollbackQuietly(connection);
                    if (winner != null) {
                        return winner;
                    }
                    throw collided;
                }

                // Step 3: Authority lease verification with row locking
                String authSql = (dialect == Dialect.SQLITE)
                        ? "SELECT authoritative_node, authority_epoch, lease_expires_at, CURRENT_TIMESTAMP AS db_now FROM island_authorities WHERE island_id = ?"
                        : "SELECT authoritative_node, authority_epoch, lease_expires_at, CURRENT_TIMESTAMP AS db_now FROM island_authorities WHERE island_id = ? FOR UPDATE";

                try (PreparedStatement ps = connection.prepareStatement(authSql)) {
                    ps.setString(1, islandId.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            updateProcessedOp(connection, operationId, "REJECTED", "AUTHORITY_NOT_FOUND");
                            tx.commit(connection);
                            return new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                                    "Island authority record not found");
                        }

                        String authoritativeNode = rs.getString("authoritative_node");
                        long authorityEpoch = rs.getLong("authority_epoch");
                        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
                        Timestamp dbNow = rs.getTimestamp("db_now");

                        if (!authoritativeNode.equals(currentNode)) {
                            updateProcessedOp(connection, operationId, "REJECTED", "AUTHORITY_NODE_MISMATCH");
                            tx.commit(connection);
                            return new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                                    "Authority lease held by node: " + authoritativeNode);
                        }

                        if (authorityEpoch != expectedEpoch) {
                            updateProcessedOp(connection, operationId, "REJECTED", "STALE_AUTHORITY_EPOCH");
                            tx.commit(connection);
                            return new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                                    "Stale authority epoch: expected " + expectedEpoch + " but was " + authorityEpoch);
                        }

                        if (leaseExpiresAt == null || leaseExpiresAt.before(dbNow)) {
                            updateProcessedOp(connection, operationId, "REJECTED", "AUTHORITY_LEASE_EXPIRED");
                            tx.commit(connection);
                            return new BankTransactionOutcome.AuthorityRejected(
                                    BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY,
                                    "Authority lease has expired");
                        }
                    }
                }

                // Step 4: Lock & read island_banks
                String bankSql = (dialect == Dialect.SQLITE)
                        ? "SELECT primary_balance_minor_units, crystals_balance, exp_balance, version FROM island_banks WHERE island_id = ?"
                        : "SELECT primary_balance_minor_units, crystals_balance, exp_balance, version FROM island_banks WHERE island_id = ? FOR UPDATE";

                long primary;
                long crystals;
                long exp;
                long actualVersion;

                try (PreparedStatement ps = connection.prepareStatement(bankSql)) {
                    ps.setString(1, islandId.value().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            updateProcessedOp(connection, operationId, "REJECTED", "BANK_NOT_FOUND");
                            tx.commit(connection);
                            return new BankTransactionOutcome.BankNotFound("Bank not found for island " + islandId);
                        }
                        primary = rs.getLong("primary_balance_minor_units");
                        crystals = rs.getLong("crystals_balance");
                        exp = rs.getLong("exp_balance");
                        actualVersion = rs.getLong("version");
                    }
                }

                // Check OCC version
                if (actualVersion != expectedVersion) {
                    updateProcessedOp(connection, operationId, "REJECTED", "STALE_OCC_VERSION");
                    tx.commit(connection);
                    return new BankTransactionOutcome.StaleVersion(expectedVersion, actualVersion);
                }

                // Balance check
                long currentBal =
                        switch (upperCurrency) {
                            case "PRIMARY" -> primary;
                            case "CRYSTALS" -> crystals;
                            case "EXP" -> exp;
                            default -> throw new IllegalStateException("Unexpected currency: " + upperCurrency);
                        };

                long newBal = currentBal + deltaAmountMinorUnits;
                if (newBal < 0) {
                    updateProcessedOp(connection, operationId, "REJECTED", "INSUFFICIENT_FUNDS");
                    tx.commit(connection);
                    return new BankTransactionOutcome.InsufficientFunds(currentBal, deltaAmountMinorUnits);
                }

                long newPrimary = upperCurrency.equals("PRIMARY") ? newBal : primary;
                long newCrystals = upperCurrency.equals("CRYSTALS") ? newBal : crystals;
                long newExp = upperCurrency.equals("EXP") ? newBal : exp;

                // Step 5: Update island_banks
                String updateBankSql = """
                        UPDATE island_banks
                        SET primary_balance_minor_units = ?,
                            crystals_balance = ?,
                            exp_balance = ?,
                            version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE island_id = ? AND version = ?
                        """;
                try (PreparedStatement ps = connection.prepareStatement(updateBankSql)) {
                    ps.setLong(1, newPrimary);
                    ps.setLong(2, newCrystals);
                    ps.setLong(3, newExp);
                    ps.setString(4, islandId.value().toString());
                    ps.setLong(5, expectedVersion);
                    int updated = ps.executeUpdate();
                    if (updated != 1) {
                        tx.rollbackQuietly(connection);
                        return new BankTransactionOutcome.StaleVersion(expectedVersion, actualVersion);
                    }
                }

                // Step 5.5: The write this charge pays for, in the same transaction or not at all.
                if (step != null && !step.apply(connection)) {
                    tx.rollbackQuietly(connection);
                    return null;
                }

                // Step 6: Append bank_transactions audit record
                UUID txId = UUID.randomUUID();
                String insertTxSql = """
                        INSERT INTO bank_transactions (
                            transaction_id, operation_id, island_id, actor_uuid,
                            currency_id, currency_scale, delta_amount_minor_units,
                            resulting_balance_minor_units, reason, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """;
                try (PreparedStatement ps = connection.prepareStatement(insertTxSql)) {
                    ps.setString(1, txId.toString());
                    ps.setString(2, operationId.toString());
                    ps.setString(3, islandId.value().toString());
                    ps.setString(4, actorUuid.toString());
                    ps.setString(5, upperCurrency);
                    ps.setInt(6, currencyScale);
                    ps.setLong(7, deltaAmountMinorUnits);
                    ps.setLong(8, newBal);
                    ps.setString(9, reason);
                    ps.executeUpdate();
                }

                // Step 7: Finalize processed_operations to APPLIED
                JsonObject payloadObj = new JsonObject();
                payloadObj.addProperty("status", "SUCCESS");
                payloadObj.addProperty("islandId", islandId.value().toString());
                payloadObj.addProperty("actorId", actorUuid.toString());
                payloadObj.addProperty("amount", deltaAmountMinorUnits);
                payloadObj.addProperty("currencyId", upperCurrency);
                payloadObj.addProperty("scope", effectiveScope);
                payloadObj.addProperty("reason", reason);
                payloadObj.addProperty("newBalanceMinorUnits", newBal);
                payloadObj.addProperty("transactionId", txId.toString());
                String resultPayload = payloadObj.toString();
                updateProcessedOp(connection, operationId, "APPLIED", "SUCCESS", resultPayload);

                // Step 7.5: Stage outbox event atomically in same transaction
                if (outboxEvent != null) {
                    com.uxplima.uxmskyblock.persistence.event.OutboxSqlHelper.stageEvent(connection, outboxEvent);
                }

                // Step 8: Commit transaction
                tx.commit(connection);

                IslandBank updatedBank =
                        new IslandBank(islandId, newPrimary, newCrystals, newExp, expectedVersion + 1, Instant.now());
                BankTransaction transaction = new BankTransaction(
                        txId,
                        operationId,
                        islandId,
                        actorUuid,
                        upperCurrency,
                        currencyScale,
                        deltaAmountMinorUnits,
                        newBal,
                        reason,
                        Instant.now());

                return new BankTransactionOutcome.Success(updatedBank, transaction);

            } catch (SQLException e) {
                tx.rollbackQuietly(connection);
                throw e;
            }
        } catch (SQLException e) {
            throw new IslandBankPersistenceException(
                    "Failed to execute bank transaction for island " + islandId + " (operation " + operationId + ")",
                    e);
        }
    }

    @Override
    public int purgeSettledOperationsBefore(java.time.Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        // Settled only. A record that never settled is a reservation a crash left behind, and
        // deleting one is exactly what would let the operation it was reserving run a second time.
        String sql = "DELETE FROM processed_operations WHERE status IN ('APPLIED', 'REJECTED') "
                + "AND completed_at IS NOT NULL AND completed_at < ?";
        try (Connection connection = database.connection();
                PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setTimestamp(1, java.sql.Timestamp.from(before));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IslandBankPersistenceException("Failed to sweep settled bank operations", e);
        }
    }

    private void updateProcessedOp(Connection connection, UUID operationId, String status, String resultCode)
            throws SQLException {
        updateProcessedOp(connection, operationId, status, resultCode, null);
    }

    private void updateProcessedOp(
            Connection connection, UUID operationId, String status, String resultCode, @Nullable String resultPayload)
            throws SQLException {
        String updateSql = (dialect == Dialect.POSTGRES) ? """
                UPDATE processed_operations
                SET status = ?, result_code = ?, result_payload = CAST(? AS jsonb), completed_at = CURRENT_TIMESTAMP
                WHERE operation_id = ?
                """ : """
                UPDATE processed_operations
                SET status = ?, result_code = ?, result_payload = ?, completed_at = CURRENT_TIMESTAMP
                WHERE operation_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setString(1, status);
            ps.setString(2, resultCode);
            ps.setString(3, resultPayload);
            ps.setString(4, operationId.toString());
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<IslandBank> findBankByIslandId(IslandId islandId) {
        return ledger.findBankByIslandId(islandId);
    }

    @Override
    public IslandBank createBank(IslandId islandId) {
        return ledger.createBank(islandId);
    }

    @Override
    public List<BankTransaction> getTransactionHistory(IslandId islandId, int limit) {
        return ledger.getTransactionHistory(islandId, limit);
    }

    /** What processed_operations already holds for this request, or null when it holds nothing. */
    private static BankTransactionOutcome.@Nullable DuplicateOperation recordedOperation(
            Connection connection, String scope, UUID actorUuid, String idempotencyKey, UUID operationId)
            throws SQLException {
        String checkOpSql = """
                SELECT operation_id, status, result_code, result_payload
                FROM processed_operations
                WHERE (operation_scope = ? AND actor_id = ? AND idempotency_key = ?)
                   OR operation_id = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(checkOpSql)) {
            ps.setString(1, scope);
            ps.setString(2, actorUuid.toString());
            ps.setString(3, idempotencyKey);
            ps.setString(4, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                UUID existingOpId = UUID.fromString(rs.getString("operation_id"));
                String status = rs.getString("status");
                String resultCode = rs.getString("result_code");
                String resultPayload = rs.getString("result_payload");
                return new BankTransactionOutcome.DuplicateOperation(
                        existingOpId,
                        "Operation already recorded with status " + status + " (" + resultCode + ")",
                        status,
                        resultCode,
                        resultPayload);
            }
        }
    }
}
