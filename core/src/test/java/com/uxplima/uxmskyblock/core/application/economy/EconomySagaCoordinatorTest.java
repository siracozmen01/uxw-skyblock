package com.uxplima.uxmskyblock.core.application.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.economy.EconomySagaRecord;
import com.uxplima.uxmskyblock.core.domain.economy.SagaId;
import com.uxplima.uxmskyblock.core.domain.economy.SagaState;
import com.uxplima.uxmskyblock.core.domain.economy.SagaType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EconomySagaCoordinatorTest {

    private InMemoryEconomySagaPort sagaPort;
    private ExternalWalletPort walletPort;
    private IslandBankService bankService;
    private EconomySagaCoordinator coordinator;

    private final PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
    private final ProfileId profileId = new ProfileId(UUID.randomUUID());
    private final IslandId islandId = new IslandId(UUID.randomUUID());
    private final ServerNodeId nodeId = new ServerNodeId("test-node");
    private final Instant now = Instant.parse("2026-09-18T10:00:00Z");

    private final IslandBank dummyBank = mock(IslandBank.class);
    private final BankTransaction dummyTx = mock(BankTransaction.class);
    private final BankTransactionOutcome.Success successOutcome =
            new BankTransactionOutcome.Success(dummyBank, dummyTx);

    @BeforeEach
    void setUp() {
        sagaPort = new InMemoryEconomySagaPort();
        walletPort = mock(ExternalWalletPort.class);
        bankService = mock(IslandBankService.class);
        coordinator = new EconomySagaCoordinator(sagaPort, walletPort, bankService, Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("executeDeposit successfully commits saga when wallet and bank operations succeed")
    void depositSuccess() {
        SagaId sagaId = SagaId.random();
        when(walletPort.withdraw(playerUuid, 1000L)).thenReturn(true);
        when(bankService.moveOnce(profileId, playerUuid, 1000L, "Player deposit", nodeId, "saga:" + sagaId.value()))
                .thenReturn(successOutcome);

        BankTransactionOutcome outcome =
                coordinator.executeDeposit(sagaId, playerUuid, profileId, islandId, 1000L, "VAULT", nodeId, now);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(sagaPort.findSagaById(sagaId)).isPresent();
        assertThat(sagaPort.findSagaById(sagaId).get().state()).isEqualTo(SagaState.COMMITTED);
    }

    @Test
    @DisplayName("executeDeposit fails immediately if wallet withdrawal fails")
    void depositWalletFails() {
        SagaId sagaId = SagaId.random();
        when(walletPort.withdraw(playerUuid, 1000L)).thenReturn(false);

        BankTransactionOutcome outcome =
                coordinator.executeDeposit(sagaId, playerUuid, profileId, islandId, 1000L, "VAULT", nodeId, now);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        verify(bankService, never()).moveOnce(any(), any(), any(Long.class), any(), any(), any());
        assertThat(sagaPort.findSagaById(sagaId).get().state()).isEqualTo(SagaState.FAILED);
    }

    @Test
    @DisplayName("executeDeposit compensates and rolls back when bank deposit fails and wallet refund succeeds")
    void depositBankFailsCompensated() {
        SagaId sagaId = SagaId.random();
        when(walletPort.withdraw(playerUuid, 1000L)).thenReturn(true);
        when(bankService.moveOnce(profileId, playerUuid, 1000L, "Player deposit", nodeId, "saga:" + sagaId.value()))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("Bank full"));
        when(walletPort.deposit(playerUuid, 1000L)).thenReturn(true);

        BankTransactionOutcome outcome =
                coordinator.executeDeposit(sagaId, playerUuid, profileId, islandId, 1000L, "VAULT", nodeId, now);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        verify(walletPort).deposit(playerUuid, 1000L);
        assertThat(sagaPort.findSagaById(sagaId).get().state()).isEqualTo(SagaState.ROLLED_BACK);
    }

    @Test
    @DisplayName("executeWithdraw successfully commits saga when bank and wallet succeed")
    void withdrawSuccess() {
        SagaId sagaId = SagaId.random();
        when(bankService.moveOnce(profileId, playerUuid, -1000L, "Player withdrawal", nodeId, "saga:" + sagaId.value()))
                .thenReturn(successOutcome);
        when(walletPort.deposit(playerUuid, 1000L)).thenReturn(true);

        BankTransactionOutcome outcome =
                coordinator.executeWithdraw(sagaId, playerUuid, profileId, islandId, 1000L, "VAULT", nodeId, now);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.Success.class);
        assertThat(sagaPort.findSagaById(sagaId)).isPresent();
        assertThat(sagaPort.findSagaById(sagaId).get().state()).isEqualTo(SagaState.COMMITTED);
    }

    @Test
    @DisplayName("executeWithdraw compensates and refunds bank when wallet deposit fails")
    void withdrawWalletFailsCompensated() {
        SagaId sagaId = SagaId.random();
        when(bankService.moveOnce(profileId, playerUuid, -1000L, "Player withdrawal", nodeId, "saga:" + sagaId.value()))
                .thenReturn(successOutcome);
        when(walletPort.deposit(playerUuid, 1000L)).thenReturn(false);
        when(bankService.moveOnce(
                        profileId, playerUuid, 1000L, "Withdrawal refund", nodeId, "saga-refund:" + sagaId.value()))
                .thenReturn(successOutcome);

        BankTransactionOutcome outcome =
                coordinator.executeWithdraw(sagaId, playerUuid, profileId, islandId, 1000L, "VAULT", nodeId, now);

        assertThat(outcome).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        verify(bankService)
                .moveOnce(profileId, playerUuid, 1000L, "Withdrawal refund", nodeId, "saga-refund:" + sagaId.value());
        assertThat(sagaPort.findSagaById(sagaId).get().state()).isEqualTo(SagaState.ROLLED_BACK);
    }

    @Test
    @DisplayName("recoverIncompleteSagas resolves compensating deposit and timeout sagas")
    void recoveryResolvesIncompleteSagas() {
        SagaId saga1 = SagaId.random();
        EconomySagaRecord rec1 = new EconomySagaRecord(
                saga1,
                playerUuid,
                profileId,
                islandId,
                SagaType.DEPOSIT,
                SagaState.COMPENSATING,
                500L,
                "VAULT",
                now.minusSeconds(10),
                now.minusSeconds(60),
                now.minusSeconds(10));
        sagaPort.createSaga(rec1);

        SagaId saga2 = SagaId.random();
        EconomySagaRecord rec2 = new EconomySagaRecord(
                saga2,
                playerUuid,
                profileId,
                islandId,
                SagaType.WITHDRAW,
                SagaState.STARTED,
                300L,
                "VAULT",
                now.minusSeconds(5),
                now.minusSeconds(50),
                now.minusSeconds(5));
        sagaPort.createSaga(rec2);

        when(walletPort.deposit(playerUuid, 500L)).thenReturn(true);

        int recovered = coordinator.recoverIncompleteSagas(now, nodeId);

        assertThat(recovered).isEqualTo(2);
        assertThat(sagaPort.findSagaById(saga1).get().state()).isEqualTo(SagaState.ROLLED_BACK);
        assertThat(sagaPort.findSagaById(saga2).get().state()).isEqualTo(SagaState.FAILED);
    }

    @Test
    @DisplayName("A withdrawal refund repeated by recovery is keyed, so the bank pays it once")
    void aRepeatedBankRefundLandsOnce() {
        SagaId sagaId = SagaId.random();
        sagaPort.createSaga(stuck(sagaId, SagaType.WITHDRAW, SagaState.COMPENSATING, 700L));
        // The refund landed before the server stopped; the bank answers the same key as done.
        when(bankService.moveOnce(
                        profileId, playerUuid, 700L, "Withdrawal refund", nodeId, "saga-refund:" + sagaId.value()))
                .thenReturn(new BankTransactionOutcome.DuplicateOperation(
                        UUID.randomUUID(), "already", "APPLIED", "SUCCESS", null));

        coordinator.recoverIncompleteSagas(now, nodeId);

        verify(bankService)
                .moveOnce(profileId, playerUuid, 700L, "Withdrawal refund", nodeId, "saga-refund:" + sagaId.value());
        assertThat(sagaPort.findSagaById(sagaId).orElseThrow().state())
                .describedAs("a refund that already landed settles the saga, it is not called a failure")
                .isEqualTo(SagaState.ROLLED_BACK);
    }

    @Test
    @DisplayName("A wallet refund that may already have been paid is not paid again")
    void aWalletRefundIsNeverRepeated() {
        SagaId sagaId = SagaId.random();
        sagaPort.createSaga(stuck(sagaId, SagaType.DEPOSIT, SagaState.REFUNDING_WALLET, 400L));

        int recovered = coordinator.recoverIncompleteSagas(now, nodeId);

        assertThat(recovered).isEqualTo(1);
        verify(walletPort, never()).deposit(any(), any(Long.class));
        assertThat(sagaPort.findSagaById(sagaId).orElseThrow().state()).isEqualTo(SagaState.FAILED);
    }

    @Test
    @DisplayName("A deposit's wallet refund is written as started before the wallet is asked")
    void theRefundIsMarkedBeforeTheWalletIsAsked() {
        SagaId sagaId = SagaId.random();
        when(walletPort.withdraw(playerUuid, 1000L)).thenReturn(true);
        when(bankService.moveOnce(profileId, playerUuid, 1000L, "Player deposit", nodeId, "saga:" + sagaId.value()))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("Bank full"));
        List<SagaState> seenWhenAsked = new java.util.ArrayList<>();
        when(walletPort.deposit(playerUuid, 1000L)).thenAnswer(ask -> {
            seenWhenAsked.add(sagaPort.findSagaById(sagaId).orElseThrow().state());
            return true;
        });

        coordinator.executeDeposit(sagaId, playerUuid, profileId, islandId, 1000L, "VAULT", nodeId, now);

        assertThat(seenWhenAsked).containsExactly(SagaState.REFUNDING_WALLET);
    }

    private EconomySagaRecord stuck(SagaId sagaId, SagaType type, SagaState state, long amount) {
        return new EconomySagaRecord(
                sagaId,
                playerUuid,
                profileId,
                islandId,
                type,
                state,
                amount,
                "VAULT",
                now.minusSeconds(10),
                now.minusSeconds(60),
                now.minusSeconds(10));
    }

    private static class InMemoryEconomySagaPort implements EconomySagaPort {
        private final Map<SagaId, EconomySagaRecord> map = new HashMap<>();

        @Override
        public void createSaga(EconomySagaRecord saga) {
            map.put(saga.sagaId(), saga);
        }

        @Override
        public void updateState(SagaId sagaId, SagaState newState, Instant updatedAt) {
            EconomySagaRecord existing = map.get(sagaId);
            if (existing != null) {
                map.put(sagaId, existing.withState(newState, updatedAt));
            }
        }

        @Override
        public Optional<EconomySagaRecord> findSagaById(SagaId sagaId) {
            return Optional.ofNullable(map.get(sagaId));
        }

        @Override
        public List<EconomySagaRecord> findIncompleteSagas(Instant expiredBefore) {
            return map.values().stream()
                    .filter(s -> (s.state() == SagaState.STARTED
                                    || s.state() == SagaState.COMPENSATING
                                    || s.state() == SagaState.REFUNDING_WALLET)
                            && !s.expiresAt().isAfter(expiredBefore))
                    .toList();
        }

        @Override
        public int purgeSettledBefore(Instant before) {
            int held = map.size();
            map.values()
                    .removeIf(saga -> (saga.state() == SagaState.COMMITTED || saga.state() == SagaState.ROLLED_BACK)
                            && saga.updatedAt().isBefore(before));
            return held - map.size();
        }
    }
}
