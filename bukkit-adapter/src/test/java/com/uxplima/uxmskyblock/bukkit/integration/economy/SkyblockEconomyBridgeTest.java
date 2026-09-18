package com.uxplima.uxmskyblock.bukkit.integration.economy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.hook.economy.EconomyBridge;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.economy.EconomySagaCoordinator;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SkyblockEconomyBridgeTest {

    private EconomyBridge mockEconomy;
    private IslandBankService mockBankService;
    private SchedulerPort directScheduler;
    private SkyblockEconomyBridge bridge;

    private Player mockPlayer;
    private UUID playerUuid;
    private ProfileId profileId;
    private ServerNodeId nodeId;
    private IslandId islandId;
    private IslandBank sampleBank;
    private BankTransaction sampleTx;

    @BeforeEach
    void setUp() {
        mockEconomy = mock(EconomyBridge.class);
        mockBankService = mock(IslandBankService.class);
        directScheduler = new DirectSchedulerPort();
        bridge = new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler);

        mockPlayer = mock(Player.class);
        playerUuid = UUID.randomUUID();
        when(mockPlayer.getUniqueId()).thenReturn(playerUuid);
        profileId = new ProfileId(playerUuid);
        nodeId = ServerNodeId.of("node-1");
        islandId = new IslandId(UUID.randomUUID());
        sampleBank = new IslandBank(islandId, 10000L, 0L, 0L, 2L, Instant.now());
        sampleTx = new BankTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                islandId,
                playerUuid,
                "PRIMARY",
                2,
                5000L,
                10000L,
                "Test",
                Instant.now());
        when(mockBankService.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
    }

    @Test
    @DisplayName("rejects zero or negative deposit and withdraw amounts")
    void rejectsZeroOrNegativeAmounts() {
        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 0, nodeId, outcomeRef::set);
        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        bridge.withdrawFromIslandBank(mockPlayer, profileId, -5, nodeId, outcomeRef::set);
        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);

        verify(mockEconomy, never()).withdraw(any(), anyDouble());
        verify(mockBankService, never()).deposit(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("deposit with economy withdraws from wallet and deposits to island bank")
    void depositWithEconomySuccess() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(mockPlayer, 50.0)).thenReturn(true);
        when(mockEconomy.withdraw(mockPlayer, 50.0)).thenReturn(true);
        when(mockBankService.deposit(eq(profileId), any(PlayerUuid.class), eq(5000L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 50, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.Success.class);
        verify(mockEconomy).withdraw(mockPlayer, 50.0);
        verify(mockBankService).deposit(eq(profileId), any(PlayerUuid.class), eq(5000L), eq(nodeId));
        verify(mockEconomy, never()).deposit(any(), anyDouble());
    }

    @Test
    @DisplayName("deposit refunds wallet when island bank transaction fails")
    void depositRefundsOnBankFailure() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(mockPlayer, 100.0)).thenReturn(true);
        when(mockEconomy.withdraw(mockPlayer, 100.0)).thenReturn(true);
        when(mockBankService.deposit(eq(profileId), any(PlayerUuid.class), eq(10000L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("Authority lock failed"));

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 100, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        // Assert compensation refund was issued to player wallet
        verify(mockEconomy).deposit(mockPlayer, 100.0);
    }

    @Test
    @DisplayName("deposit fails immediately without deducting wallet if player has insufficient funds")
    void depositFailsOnInsufficientWalletFunds() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockEconomy.has(mockPlayer, 500.0)).thenReturn(false);
        when(mockEconomy.format(500.0)).thenReturn("$500.00");

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.depositToIslandBank(mockPlayer, profileId, 500, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.InsufficientFunds.class);
        verify(mockEconomy, never()).withdraw(any(), anyDouble());
        verify(mockBankService, never()).deposit(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("withdraw gives money to wallet when island bank transaction succeeds")
    void withdrawGivesFundsToWallet() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockBankService.withdraw(eq(profileId), any(PlayerUuid.class), eq(2500L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));
        when(mockEconomy.deposit(mockPlayer, 25.0)).thenReturn(true);

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.withdrawFromIslandBank(mockPlayer, profileId, 25, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.Success.class);
        verify(mockEconomy).deposit(mockPlayer, 25.0);
    }

    @Test
    @DisplayName("withdraw refunds island bank if wallet deposit fails")
    void withdrawRefundsBankOnWalletDepositFailure() {
        when(mockEconomy.isPresent()).thenReturn(true);
        when(mockBankService.withdraw(eq(profileId), any(PlayerUuid.class), eq(2500L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));
        when(mockEconomy.deposit(mockPlayer, 25.0)).thenReturn(false);

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        bridge.withdrawFromIslandBank(mockPlayer, profileId, 25, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.AuthorityRejected.class);
        // Bank deposit called to refund the bank
        verify(mockBankService).deposit(eq(profileId), any(PlayerUuid.class), eq(2500L), eq(nodeId));
    }

    @Test
    @DisplayName("deposit with saga coordinator routes through executeDeposit")
    void depositWithSagaCoordinatorRoutesCorrectly() {
        EconomySagaCoordinator mockCoordinator = mock(EconomySagaCoordinator.class);
        SkyblockEconomyBridge sagaBridge =
                new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler, mockCoordinator);

        when(mockEconomy.isPresent()).thenReturn(false);
        when(mockCoordinator.executeDeposit(
                        any(),
                        any(PlayerUuid.class),
                        eq(profileId),
                        eq(islandId),
                        eq(5000L),
                        eq("VAULT"),
                        eq(nodeId),
                        any(Instant.class)))
                .thenReturn(new BankTransactionOutcome.Success(sampleBank, sampleTx));

        AtomicReference<BankTransactionOutcome> outcomeRef = new AtomicReference<>();
        sagaBridge.depositToIslandBank(mockPlayer, profileId, 50, nodeId, outcomeRef::set);

        assertThat(outcomeRef.get()).isInstanceOf(BankTransactionOutcome.Success.class);
        verify(mockCoordinator)
                .executeDeposit(
                        any(),
                        any(PlayerUuid.class),
                        eq(profileId),
                        eq(islandId),
                        eq(5000L),
                        eq("VAULT"),
                        eq(nodeId),
                        any(Instant.class));
    }

    @Test
    @DisplayName("recoverPendingSagas delegates to saga coordinator")
    void recoverPendingSagasDelegates() {
        EconomySagaCoordinator mockCoordinator = mock(EconomySagaCoordinator.class);
        SkyblockEconomyBridge sagaBridge =
                new SkyblockEconomyBridge(mockEconomy, mockBankService, directScheduler, mockCoordinator);

        when(mockCoordinator.recoverIncompleteSagas(any(Instant.class), eq(nodeId)))
                .thenReturn(3);

        int recovered = sagaBridge.recoverPendingSagas(nodeId);
        assertThat(recovered).isEqualTo(3);
        verify(mockCoordinator).recoverIncompleteSagas(any(Instant.class), eq(nodeId));
    }

    private static class DirectSchedulerPort implements SchedulerPort {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }
    }
}
