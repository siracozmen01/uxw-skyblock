package com.uxplima.uxmskyblock.core.application.bank;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Application service managing island bank balances and authority-fenced transactions.
 */
public final class IslandBankService {

    private final IslandBankPort islandBankPort;
    private final IslandStoragePort islandStoragePort;
    private final IslandAuthorityPort islandAuthorityPort;

    public IslandBankService(
            IslandBankPort islandBankPort,
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort) {
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort must not be null");
    }

    public Optional<Long> getBalanceMinorUnits(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return Optional.empty();
        }
        return islandBankPort.findBankByIslandId(optIslandId.get()).map(IslandBank::primaryBalanceMinorUnits);
    }

    public BankTransactionOutcome deposit(
            ProfileId profileId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        return execute(profileId, playerUuid, amountMinorUnits, "Player deposit", serverNodeId);
    }

    public BankTransactionOutcome withdraw(
            ProfileId profileId, PlayerUuid playerUuid, long amountMinorUnits, ServerNodeId serverNodeId) {
        return execute(profileId, playerUuid, -amountMinorUnits, "Player withdrawal", serverNodeId);
    }

    private BankTransactionOutcome execute(
            ProfileId profileId,
            PlayerUuid playerUuid,
            long deltaMinorUnits,
            String reason,
            ServerNodeId serverNodeId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");

        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            return new BankTransactionOutcome.AuthorityRejected("No island associated with profile " + profileId);
        }

        IslandId islandId = optIslandId.get();
        Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(islandId);
        IslandBank bank = optBank.orElseGet(() -> islandBankPort.createBank(islandId));

        Optional<IslandAuthorityRecord> optAuth = islandAuthorityPort.findAuthority(islandId);
        long epoch = 1L;
        if (optAuth.isPresent()) {
            IslandAuthorityRecord auth = optAuth.get();
            epoch = auth.authorityEpoch();
        } else {
            IslandAuthorityOutcome outcome = islandAuthorityPort.acquireAuthority(islandId, serverNodeId, 86400);
            if (outcome instanceof IslandAuthorityOutcome.Success s) {
                epoch = s.epoch();
            }
        }

        UUID operationId = UUID.randomUUID();
        String idempotencyKey = "tx-" + operationId;

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
                idempotencyKey);
    }
}
