package com.uxplima.uxmskyblock.core.application.bank;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Outbound application port defining authoritative operations on island bank accounts.
 */
public interface IslandBankPort {

    /**
     * Finds the bank account for the given island ID.
     *
     * @param islandId target island ID
     * @return optional containing the island bank snapshot if found
     */
    Optional<IslandBank> findBankByIslandId(IslandId islandId);

    /**
     * Creates an initial zero-balance bank account for the given island ID if not existing.
     *
     * @param islandId target island ID
     * @return the initialized bank account
     */
    IslandBank createBank(IslandId islandId);

    /**
     * Atomically executes an economic transaction on the island bank.
     *
     * <p>Enforces:
     * <ol>
     *   <li>Authority lease verification (node match, epoch match, unexpired lease)</li>
     *   <li>Idempotency check via operation ID and idempotency key</li>
     *   <li>Optimistic concurrency version validation on the bank record</li>
     *   <li>Non-negative balance invariant (insufficient funds rejection)</li>
     *   <li>Immutable audit log append</li>
     * </ol>
     *
     * @param islandId target island ID
     * @param actorUuid player or system actor executing the mutation
     * @param currencyId currency identifier (PRIMARY, CRYSTALS, EXP)
     * @param currencyScale decimal scale of currency
     * @param deltaAmountMinorUnits delta amount (positive for deposit, negative for withdrawal)
     * @param reason audit reason description
     * @param currentNode cluster node identifier asserting authority
     * @param expectedEpoch authority lease epoch
     * @param expectedVersion expected optimistic concurrency version of the bank
     * @param operationId unique operation ID for idempotency reservation
     * @param idempotencyKey client-scoped idempotency key
     * @return sealed outcome describing success or specific rejection cause
     */
    BankTransactionOutcome executeTransaction(
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
            String idempotencyKey);

    default BankTransactionOutcome executeTransaction(
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
                idempotencyKey);
    }

    default BankTransactionOutcome executeTransaction(
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
                outboxEvent);
    }

    /**
     * Retrieves recent transaction audit history for an island.
     *
     * @param islandId target island ID
     * @param limit maximum records to retrieve
     * @return immutable list of bank transactions in reverse chronological order
     */
    List<BankTransaction> getTransactionHistory(IslandId islandId, int limit);
}
