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

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(EconomySagaCoordinator.class.getName());

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
        // Written as soon as the wallet paid, so a crash from here on leaves a saga recovery can
        // finish: the bank move is keyed and can be asked again.
        sagaPort.updateState(sagaId, SagaState.WALLET_DEBITED, now);

        BankTransactionOutcome outcome = bankService.moveOnce(
                profileId, playerUuid, amountMinorUnits, "Player deposit", nodeId, forwardKey(sagaId));
        if (outcome instanceof BankTransactionOutcome.Success) {
            sagaPort.updateState(sagaId, SagaState.COMMITTED, now);
            return outcome;
        }

        // Compensation phase: refund personal wallet. The state is written first, because the
        // wallet cannot say afterwards whether it was paid.
        sagaPort.updateState(sagaId, SagaState.REFUNDING_WALLET, now);
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

        BankTransactionOutcome outcome = bankService.moveOnce(
                profileId, playerUuid, -amountMinorUnits, "Player withdrawal", nodeId, forwardKey(sagaId));
        if (!(outcome instanceof BankTransactionOutcome.Success)) {
            sagaPort.updateState(sagaId, SagaState.FAILED, now);
            return outcome;
        }

        sagaPort.updateState(sagaId, SagaState.CREDITING_WALLET, now);
        boolean deposited = walletPort.deposit(playerUuid, amountMinorUnits);
        if (deposited) {
            sagaPort.updateState(sagaId, SagaState.COMMITTED, now);
            return outcome;
        }

        // Compensation phase: refund Island Bank
        sagaPort.updateState(sagaId, SagaState.COMPENSATING, now);
        BankTransactionOutcome refundOutcome = bankService.moveOnce(
                profileId, playerUuid, amountMinorUnits, "Withdrawal refund", nodeId, refundKey(sagaId));
        if (IslandBankService.landed(refundOutcome)) {
            sagaPort.updateState(sagaId, SagaState.ROLLED_BACK, now);
        } else {
            sagaPort.updateState(sagaId, SagaState.FAILED, now);
        }
        return new BankTransactionOutcome.AuthorityRejected(
                BankTransactionOutcome.AuthorityRejected.Kind.WALLET_REFUSED,
                "Failed to deposit funds into your personal wallet. Bank funds refunded.");
    }

    /**
     * Settles sagas a crash left unfinished.
     *
     * <p>Recovery used to repeat every compensation it found, and a compensation that had already
     * run before the crash was run again: the wallet or the bank was paid twice. A bank refund is now
     * keyed, so repeating it lands once. A wallet refund cannot be keyed, so it is marked as started
     * before the wallet is asked; a saga found in that state is not paid again but marked failed and
     * logged for an operator to settle by hand.
     */
    public int recoverIncompleteSagas(Instant now, ServerNodeId nodeId) {
        List<EconomySagaRecord> incomplete = sagaPort.findIncompleteSagas(now);
        int recovered = 0;
        for (EconomySagaRecord saga : incomplete) {
            switch (saga.state()) {
                case REFUNDING_WALLET -> {
                    LOGGER.warning(() -> "Economy saga " + saga.sagaId() + " was giving " + saga.amountMinorUnits()
                            + " back to the wallet of " + saga.playerUuid() + " when the server stopped. The"
                            + " wallet cannot say whether it was paid, so it is not paid again. Check it by hand.");
                    sagaPort.updateState(saga.sagaId(), SagaState.FAILED, now);
                    recovered++;
                }
                case COMPENSATING -> {
                    if (saga.sagaType() == SagaType.DEPOSIT) {
                        // The refund had not started: that is written as REFUNDING_WALLET first.
                        sagaPort.updateState(saga.sagaId(), SagaState.REFUNDING_WALLET, now);
                        boolean refunded = walletPort.deposit(saga.playerUuid(), saga.amountMinorUnits());
                        sagaPort.updateState(saga.sagaId(), refunded ? SagaState.ROLLED_BACK : SagaState.FAILED, now);
                    } else {
                        BankTransactionOutcome refund = bankService.moveOnce(
                                saga.profileId(),
                                saga.playerUuid(),
                                saga.amountMinorUnits(),
                                "Withdrawal refund",
                                nodeId,
                                refundKey(saga.sagaId()));
                        sagaPort.updateState(
                                saga.sagaId(),
                                IslandBankService.landed(refund) ? SagaState.ROLLED_BACK : SagaState.FAILED,
                                now);
                    }
                    recovered++;
                }
                case WALLET_DEBITED -> {
                    finishDeposit(saga, nodeId, now);
                    recovered++;
                }
                case CREDITING_WALLET -> {
                    LOGGER.warning(() -> "Economy saga " + saga.sagaId() + " was paying " + saga.amountMinorUnits()
                            + " into the wallet of " + saga.playerUuid() + " when the server stopped. The wallet"
                            + " cannot say whether it was paid, so nothing is moved again. Check it by hand.");
                    sagaPort.updateState(saga.sagaId(), SagaState.FAILED, now);
                    recovered++;
                }
                case STARTED -> {
                    if (saga.sagaType() == SagaType.WITHDRAW) {
                        undoWithdrawal(saga, nodeId, now);
                    } else {
                        // The wallet was being asked and cannot say whether it paid.
                        LOGGER.warning(() -> "Economy saga " + saga.sagaId() + " was taking " + saga.amountMinorUnits()
                                + " from the wallet of " + saga.playerUuid() + " when the server stopped. Check it"
                                + " by hand.");
                        sagaPort.updateState(saga.sagaId(), SagaState.FAILED, now);
                    }
                    recovered++;
                }
                default -> {}
            }
        }
        return recovered;
    }

    /**
     * Puts a deposit's money into the bank, now that the wallet is known to have paid.
     *
     * <p>The bank move is keyed, so asking again lands it once whether or not it landed before the
     * server stopped. If the bank refuses, the wallet gets its money back.
     */
    private void finishDeposit(EconomySagaRecord saga, ServerNodeId nodeId, Instant now) {
        BankTransactionOutcome deposit = bankService.moveOnce(
                saga.profileId(),
                saga.playerUuid(),
                saga.amountMinorUnits(),
                "Player deposit",
                nodeId,
                forwardKey(saga.sagaId()));
        if (IslandBankService.landed(deposit)) {
            sagaPort.updateState(saga.sagaId(), SagaState.COMMITTED, now);
            return;
        }
        sagaPort.updateState(saga.sagaId(), SagaState.REFUNDING_WALLET, now);
        boolean refunded = walletPort.deposit(saga.playerUuid(), saga.amountMinorUnits());
        sagaPort.updateState(saga.sagaId(), refunded ? SagaState.ROLLED_BACK : SagaState.FAILED, now);
    }

    /**
     * Undoes a withdrawal that stopped before it paid the wallet.
     *
     * <p>Whether the bank move had landed is not known, and both answers are settled the same way:
     * the keyed withdrawal is asked again, which lands it once, and the keyed refund puts it back. The
     * bank ends where it started and the wallet was never paid.
     */
    private void undoWithdrawal(EconomySagaRecord saga, ServerNodeId nodeId, Instant now) {
        BankTransactionOutcome taken = bankService.moveOnce(
                saga.profileId(),
                saga.playerUuid(),
                -saga.amountMinorUnits(),
                "Player withdrawal",
                nodeId,
                forwardKey(saga.sagaId()));
        if (!IslandBankService.landed(taken)) {
            // Nothing was taken, so there is nothing to give back.
            sagaPort.updateState(saga.sagaId(), SagaState.FAILED, now);
            return;
        }
        BankTransactionOutcome refund = bankService.moveOnce(
                saga.profileId(),
                saga.playerUuid(),
                saga.amountMinorUnits(),
                "Withdrawal refund",
                nodeId,
                refundKey(saga.sagaId()));
        sagaPort.updateState(
                saga.sagaId(), IslandBankService.landed(refund) ? SagaState.ROLLED_BACK : SagaState.FAILED, now);
    }

    /** The key a saga's own bank move is recorded under. */
    static String forwardKey(SagaId sagaId) {
        return "saga:" + sagaId.value();
    }

    /** The key the bank refund of a withdrawal is recorded under, the same however often it is asked. */
    static String refundKey(SagaId sagaId) {
        return "saga-refund:" + sagaId.value();
    }
}
