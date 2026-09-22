package com.uxplima.uxmskyblock.core.application.economy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
import com.uxplima.uxmskyblock.core.domain.economy.SagaType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Two-phase compensating saga coordinator managing transfers between external wallets
 * and distributed island banks with durable write-ahead logging and recovery.
 */
public final class EconomySagaCoordinator {

    private final EconomySagaPort sagaPort;
    private final ExternalWalletPort walletPort;
    private final IslandBankService bankService;
    private final Duration sagaTimeout;

    public EconomySagaCoordinator(
            EconomySagaPort sagaPort,
            ExternalWalletPort walletPort,
            IslandBankService bankService,
            Duration sagaTimeout) {
        this.sagaPort = Objects.requireNonNull(sagaPort, "sagaPort");
        this.walletPort = Objects.requireNonNull(walletPort, "walletPort");
        this.bankService = Objects.requireNonNull(bankService, "bankService");
        this.sagaTimeout = Objects.requireNonNull(sagaTimeout, "sagaTimeout");
    }

    public BankTransactionOutcome executeDeposit(
            SagaId sagaId,
            PlayerUuid playerUuid,
            ProfileId profileId,
            IslandId islandId,
            long amountMinorUnits,
            String currency,
            ServerNodeId nodeId,
            Instant now) {
        if (amountMinorUnits <= 0) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT, "Deposit amount must be positive.");
        }

        Instant expiresAt = now.plus(sagaTimeout);
        EconomySagaRecord saga = EconomySagaRecord.start(
                sagaId, playerUuid, profileId, islandId, SagaType.DEPOSIT, amountMinorUnits, currency, expiresAt, now);

        sagaPort.createSaga(saga);

        if (!walletPort.withdraw(playerUuid, amountMinorUnits)) {
            sagaPort.updateState(sagaId, SagaState.FAILED, now);
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                    "Failed to withdraw funds from your wallet.");
        }

        BankTransactionOutcome outcome = bankService.deposit(profileId, playerUuid, amountMinorUnits, nodeId);
        if (outcome instanceof BankTransactionOutcome.Success) {
            sagaPort.updateState(sagaId, SagaState.COMMITTED, now);
            return outcome;
        }

        // Compensation phase: refund personal wallet
        sagaPort.updateState(sagaId, SagaState.COMPENSATING, now);
        boolean refunded = walletPort.deposit(playerUuid, amountMinorUnits);
        if (refunded) {
            sagaPort.updateState(sagaId, SagaState.ROLLED_BACK, now);
        } else {
            sagaPort.updateState(sagaId, SagaState.FAILED, now);
        }
        return outcome;
    }

    public BankTransactionOutcome executeWithdraw(
            SagaId sagaId,
            PlayerUuid playerUuid,
            ProfileId profileId,
            IslandId islandId,
            long amountMinorUnits,
            String currency,
            ServerNodeId nodeId,
            Instant now) {
        if (amountMinorUnits <= 0) {
            return new BankTransactionOutcome.AuthorityRejected(
                    BankTransactionOutcome.AuthorityRejected.Kind.INVALID_AMOUNT, "Withdraw amount must be positive.");
        }

        Instant expiresAt = now.plus(sagaTimeout);
        EconomySagaRecord saga = EconomySagaRecord.start(
                sagaId, playerUuid, profileId, islandId, SagaType.WITHDRAW, amountMinorUnits, currency, expiresAt, now);

        sagaPort.createSaga(saga);

        BankTransactionOutcome outcome = bankService.withdraw(profileId, playerUuid, amountMinorUnits, nodeId);
        if (!(outcome instanceof BankTransactionOutcome.Success)) {
            sagaPort.updateState(sagaId, SagaState.FAILED, now);
            return outcome;
        }

        boolean deposited = walletPort.deposit(playerUuid, amountMinorUnits);
        if (deposited) {
            sagaPort.updateState(sagaId, SagaState.COMMITTED, now);
            return outcome;
        }

        // Compensation phase: refund Island Bank
        sagaPort.updateState(sagaId, SagaState.COMPENSATING, now);
        BankTransactionOutcome refundOutcome = bankService.deposit(profileId, playerUuid, amountMinorUnits, nodeId);
        if (refundOutcome instanceof BankTransactionOutcome.Success) {
            sagaPort.updateState(sagaId, SagaState.ROLLED_BACK, now);
        } else {
            sagaPort.updateState(sagaId, SagaState.FAILED, now);
        }
        return new BankTransactionOutcome.AuthorityRejected(
                BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                "Failed to deposit funds into your personal wallet. Bank funds refunded.");
    }

    public int recoverIncompleteSagas(Instant now, ServerNodeId nodeId) {
        List<EconomySagaRecord> incomplete = sagaPort.findIncompleteSagas(now);
        int recovered = 0;
        for (EconomySagaRecord saga : incomplete) {
            if (saga.state() == SagaState.COMPENSATING) {
                if (saga.sagaType() == SagaType.DEPOSIT) {
                    boolean refunded = walletPort.deposit(saga.playerUuid(), saga.amountMinorUnits());
                    sagaPort.updateState(saga.sagaId(), refunded ? SagaState.ROLLED_BACK : SagaState.FAILED, now);
                } else {
                    BankTransactionOutcome refund =
                            bankService.deposit(saga.profileId(), saga.playerUuid(), saga.amountMinorUnits(), nodeId);
                    sagaPort.updateState(
                            saga.sagaId(),
                            (refund instanceof BankTransactionOutcome.Success)
                                    ? SagaState.ROLLED_BACK
                                    : SagaState.FAILED,
                            now);
                }
                recovered++;
            } else if (saga.state() == SagaState.STARTED) {
                sagaPort.updateState(saga.sagaId(), SagaState.FAILED, now);
                recovered++;
            }
        }
        return recovered;
    }
}
