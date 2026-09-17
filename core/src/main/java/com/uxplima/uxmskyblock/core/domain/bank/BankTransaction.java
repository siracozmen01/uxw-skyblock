package com.uxplima.uxmskyblock.core.domain.bank;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Immutable audit record representing a completed financial transaction on an island bank.
 *
 * @param transactionId unique transaction identifier
 * @param operationId idempotency operation identifier
 * @param islandId target island ID
 * @param actorUuid player UUID or system actor initiating the transaction
 * @param currencyId currency identifier (e.g. PRIMARY, CRYSTALS, EXP)
 * @param currencyScale decimal scale of the currency (e.g. 2 for cents)
 * @param deltaAmountMinorUnits delta amount applied (positive for deposits, negative for withdrawals)
 * @param resultingBalanceMinorUnits balance immediately following transaction execution
 * @param reason human-readable or audit reason code
 * @param createdAt timestamp of transaction execution
 */
public record BankTransaction(
        UUID transactionId,
        UUID operationId,
        IslandId islandId,
        UUID actorUuid,
        String currencyId,
        int currencyScale,
        long deltaAmountMinorUnits,
        long resultingBalanceMinorUnits,
        String reason,
        Instant createdAt) {

    public BankTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(currencyId, "currencyId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
