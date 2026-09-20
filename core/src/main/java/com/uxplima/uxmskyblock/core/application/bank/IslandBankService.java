package com.uxplima.uxmskyblock.core.application.bank;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Application service managing island bank balances and authority-fenced transactions.
 */
public final class IslandBankService {

    private final IslandBankPort islandBankPort;
    private final IslandStoragePort islandStoragePort;
    private final IslandAuthorityPort islandAuthorityPort;
    private final @Nullable OutboxPort outboxPort;

    public IslandBankService(
            IslandBankPort islandBankPort,
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            @Nullable OutboxPort outboxPort) {
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort must not be null");
        this.outboxPort = outboxPort;
    }

    public IslandBankService(
            IslandBankPort islandBankPort,
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort) {
        this(islandBankPort, islandStoragePort, islandAuthorityPort, null);
    }

    public Optional<Long> getBalanceMinorUnits(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return Optional.empty();
        }
        return islandBankPort.findBankByIslandId(optIslandId.get()).map(IslandBank::primaryBalanceMinorUnits);
    }

    public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return islandStoragePort.findIslandIdByProfileId(profileId);
    }

    public BankTransactionOutcome depositToIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, amountMinorUnits, "Bank deposit", serverNodeId);
    }

    public BankTransactionOutcome depositToIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, String reason, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, amountMinorUnits, reason, serverNodeId);
    }

    public BankTransactionOutcome withdrawFromIsland(
            IslandId islandId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(islandId, playerUuid, -amountMinorUnits, "Bank withdrawal", serverNodeId);
    }

    public BankTransactionOutcome deposit(
            ProfileId profileId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected("No island associated with profile " + profileId);
        }
        return execute(optIslandId.get(), playerUuid, amountMinorUnits, "Player deposit", serverNodeId);
    }

    public BankTransactionOutcome withdraw(
            ProfileId profileId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected("No island associated with profile " + profileId);
        }
        return execute(optIslandId.get(), playerUuid, -amountMinorUnits, "Player withdrawal", serverNodeId);
    }

    public BankTransactionOutcome depositToIsland(
            IslandId islandId,
            PlayerUuid playerUuid,
            long amountMinorUnits,
            String reason,
            ServerNodeId serverNodeId,
            UUID operationId,
            String idempotencyKey,
            String operationScope) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return execute(
                islandId,
                playerUuid,
                amountMinorUnits,
                reason,
                serverNodeId,
                operationId,
                idempotencyKey,
                operationScope);
    }

    private BankTransactionOutcome execute(
            IslandId islandId, PlayerUuid playerUuid, long deltaMinorUnits, String reason, ServerNodeId serverNodeId) {
        return execute(islandId, playerUuid, deltaMinorUnits, reason, serverNodeId, null, null, "ISLAND_BANK");
    }

    private BankTransactionOutcome execute(
            IslandId islandId,
            PlayerUuid playerUuid,
            long deltaMinorUnits,
            String reason,
            ServerNodeId serverNodeId,
            @Nullable UUID customOperationId,
            @Nullable String customIdempotencyKey,
            @Nullable String customOperationScope) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");

        Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(islandId);
        IslandBank bank = optBank.orElseGet(() -> islandBankPort.createBank(islandId));

        Optional<IslandAuthorityRecord> optAuth = islandAuthorityPort.findAuthority(islandId);
        if (optAuth.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected("No authority record found for island " + islandId);
        }

        IslandAuthorityRecord auth = optAuth.get();
        if (!auth.authoritativeNode().equals(serverNodeId)) {
            return new BankTransactionOutcome.AuthorityRejected(
                    "Local node " + serverNodeId + " does not hold authority for island " + islandId + " (held by "
                            + auth.authoritativeNode() + ")");
        }

        if (auth.leaseExpiresAt().isBefore(java.time.Instant.now())) {
            return new BankTransactionOutcome.AuthorityRejected(
                    "Authority lease expired at " + auth.leaseExpiresAt() + " for island " + islandId);
        }

        long epoch = auth.authorityEpoch();

        UUID operationId = (customOperationId != null) ? customOperationId : UUID.randomUUID();
        String idempotencyKey = (customIdempotencyKey != null) ? customIdempotencyKey : "tx-" + operationId;
        String operationScope = (customOperationScope != null) ? customOperationScope : "ISLAND_BANK";

        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_BANK_TRANSACTION",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"playerUuid\":\"%s\",\"deltaMinorUnits\":%d,\"reason\":\"%s\"}",
                                islandId.value(), playerUuid.value(), deltaMinorUnits, reason))
                : null;

        return islandBankPort.executeTransaction(
                islandId,
                playerUuid.value(),
                "PRIMARY",
                2,
                deltaMinorUnits,
                reason,
                serverNodeId.value(),
                epoch,
                bank.version(),
                operationId,
                idempotencyKey,
                operationScope,
                outboxEvent);
    }
}
