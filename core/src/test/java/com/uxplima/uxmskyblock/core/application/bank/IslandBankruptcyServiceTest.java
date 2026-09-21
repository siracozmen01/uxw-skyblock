package com.uxplima.uxmskyblock.core.application.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyCycleResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandBankruptcyServiceTest {

    private InMemoryBankruptcyStorage storage;
    private IslandBankPort mockBankPort;
    private IslandAuthorityPort mockAuthorityPort;
    private IslandUpkeepPolicy policy;
    private IslandBankruptcyService service;

    private IslandId islandId;
    private ServerNodeId nodeId;
    private Instant now;

    @BeforeEach
    void setUp() {
        storage = new InMemoryBankruptcyStorage();
        mockBankPort = mock(IslandBankPort.class);
        mockAuthorityPort = mock(IslandAuthorityPort.class);

        policy = new IslandUpkeepPolicy(
                true,
                Duration.ofHours(24),
                50_000L, // $500.00 base
                10_000L, // $100.00 per member
                Duration.ofHours(72),
                true);

        service = new IslandBankruptcyService(storage, mockBankPort, mockAuthorityPort, () -> policy);

        islandId = new IslandId(UUID.randomUUID());
        nodeId = new ServerNodeId("node-alpha");
        now = Instant.parse("2026-09-19T12:00:00Z");
    }

    @Test
    @DisplayName("Upkeep returns SkippedDisabled when upkeep policy is disabled")
    void upkeepSkippedWhenDisabled() {
        policy = new IslandUpkeepPolicy(false, Duration.ofHours(24), 50_000L, 10_000L, Duration.ofHours(72), true);

        BankruptcyCycleResult result = service.processUpkeepCycle(islandId, 3, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyCycleResult.SkippedDisabled.class);
    }

    @Test
    @DisplayName("Upkeep debits bank when funds are sufficient and keeps island solvent")
    void upkeepDebitsBankWhenSufficient() {
        // base 50k + 3*10k = 80k
        IslandBank bank = new IslandBank(islandId, 100_000L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));
        IslandBank updatedBank = new IslandBank(islandId, 20_000L, 0L, 0L, 2L, now);
        BankTransaction tx = new BankTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                islandId,
                IslandBankruptcyService.SYSTEM_UPKEEP_ACTOR,
                "PRIMARY",
                2,
                -80_000L,
                20_000L,
                "Automated island upkeep fee",
                now);
        when(mockBankPort.executeTransaction(
                        eq(islandId),
                        any(UUID.class),
                        eq("PRIMARY"),
                        eq(2),
                        eq(-80_000L),
                        anyString(),
                        eq("node-alpha"),
                        anyLong(),
                        eq(1L),
                        any(UUID.class),
                        anyString()))
                .thenReturn(new BankTransactionOutcome.Success(updatedBank, tx));

        BankruptcyCycleResult result = service.processUpkeepCycle(islandId, 3, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyCycleResult.Paid.class);
        BankruptcyCycleResult.Paid paid = (BankruptcyCycleResult.Paid) result;
        assertThat(paid.amountDebited()).isEqualTo(80_000L);
        assertThat(paid.remainingBalance()).isEqualTo(20_000L);
        assertThat(service.isIslandLocked(islandId, now)).isFalse();
    }

    @Test
    @DisplayName("Upkeep enters GRACE stage when solvent island has insufficient funds")
    void upkeepEntersGraceWhenInsufficient() {
        IslandBank bank = new IslandBank(islandId, 30_000L, 0L, 0L, 1L, now); // needs 80k
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));

        BankruptcyCycleResult result = service.processUpkeepCycle(islandId, 3, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyCycleResult.GraceEntered.class);
        BankruptcyCycleResult.GraceEntered grace = (BankruptcyCycleResult.GraceEntered) result;
        assertThat(grace.shortfall()).isEqualTo(80_000L);
        assertThat(grace.totalDebt()).isEqualTo(80_000L);
        assertThat(grace.graceUntil()).isEqualTo(now.plus(Duration.ofDays(3)));

        IslandBankruptcyRecord record = service.getBankruptcyRecord(islandId, now);
        assertThat(record.status()).isEqualTo(BankruptcyStatus.GRACE);
        assertThat(record.debtMinorUnits()).isEqualTo(80_000L);
        assertThat(service.isIslandLocked(islandId, now)).isFalse(); // Not locked yet during grace
    }

    @Test
    @DisplayName("Upkeep extends debt during active grace period without moving deadline")
    void upkeepAccumulatesDebtDuringActiveGrace() {
        Instant deadline = now.plus(Duration.ofDays(3));
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.GRACE, 80_000L, deadline, now));

        IslandBank bank = new IslandBank(islandId, 10_000L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));

        Instant nextCycle = now.plus(Duration.ofHours(24)); // Still 48h remaining
        BankruptcyCycleResult result = service.processUpkeepCycle(islandId, 1, nextCycle, nodeId); // 50k+10k = 60k fee

        assertThat(result).isInstanceOf(BankruptcyCycleResult.GraceExtended.class);
        BankruptcyCycleResult.GraceExtended extended = (BankruptcyCycleResult.GraceExtended) result;
        assertThat(extended.shortfall()).isEqualTo(60_000L);
        assertThat(extended.totalDebt()).isEqualTo(140_000L);
        assertThat(extended.graceUntil()).isEqualTo(deadline);

        assertThat(service.isIslandLocked(islandId, nextCycle)).isFalse();
    }

    @Test
    @DisplayName("Upkeep escalates to LOCKED when grace period expires")
    void upkeepEscalatesToLockedUponExpiry() {
        Instant pastDeadline = now.minus(Duration.ofHours(1)); // Expired!
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.GRACE, 80_000L, pastDeadline, now));

        IslandBank bank = new IslandBank(islandId, 0L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));

        BankruptcyCycleResult result = service.processUpkeepCycle(islandId, 0, now, nodeId); // 50k fee

        assertThat(result).isInstanceOf(BankruptcyCycleResult.LockoutApplied.class);
        BankruptcyCycleResult.LockoutApplied lockout = (BankruptcyCycleResult.LockoutApplied) result;
        assertThat(lockout.shortfall()).isEqualTo(50_000L);
        assertThat(lockout.totalDebt()).isEqualTo(130_000L);

        assertThat(service.isIslandLocked(islandId, now)).isTrue();
    }

    @Test
    @DisplayName("settleArrears instantly remediates debt when bank has sufficient funds")
    void settleArrearsRemediatesDebt() {
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.LOCKED, 120_000L, null, now));

        IslandBank bank = new IslandBank(islandId, 200_000L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));
        IslandBank updatedBank = new IslandBank(islandId, 80_000L, 0L, 0L, 2L, now);
        BankTransaction tx = new BankTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                islandId,
                IslandBankruptcyService.SYSTEM_UPKEEP_ACTOR,
                "PRIMARY",
                2,
                -120_000L,
                80_000L,
                "Settlement of island upkeep arrears",
                now);
        when(mockBankPort.executeTransaction(
                        eq(islandId),
                        any(UUID.class),
                        eq("PRIMARY"),
                        eq(2),
                        eq(-120_000L),
                        anyString(),
                        eq("node-alpha"),
                        anyLong(),
                        eq(1L),
                        any(UUID.class),
                        anyString()))
                .thenReturn(new BankTransactionOutcome.Success(updatedBank, tx));

        BankruptcyRemediationResult result = service.settleArrears(islandId, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyRemediationResult.Settled.class);
        BankruptcyRemediationResult.Settled settled = (BankruptcyRemediationResult.Settled) result;
        assertThat(settled.amountPaid()).isEqualTo(120_000L);
        assertThat(settled.remainingBalance()).isEqualTo(80_000L);

        // Island is now solvent!
        IslandBankruptcyRecord record = service.getBankruptcyRecord(islandId, now);
        assertThat(record.status()).isEqualTo(BankruptcyStatus.SOLVENT);
        assertThat(record.debtMinorUnits()).isEqualTo(0L);
        assertThat(service.isIslandLocked(islandId, now)).isFalse();
    }

    @Test
    @DisplayName("settleArrears rejects remediation when bank has insufficient funds")
    void settleArrearsFailsWhenInsufficient() {
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.LOCKED, 120_000L, null, now));

        IslandBank bank = new IslandBank(islandId, 50_000L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));

        BankruptcyRemediationResult result = service.settleArrears(islandId, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyRemediationResult.InsufficientFunds.class);
        BankruptcyRemediationResult.InsufficientFunds fail = (BankruptcyRemediationResult.InsufficientFunds) result;
        assertThat(fail.debtAmount()).isEqualTo(120_000L);
        assertThat(fail.currentBalance()).isEqualTo(50_000L);

        // Island remains locked
        assertThat(service.isIslandLocked(islandId, now)).isTrue();
    }

    @Test
    @DisplayName("A settlement the bank refused because somebody else settled first says so, not insufficient funds")
    void aRefusedSettlementIsNotInsufficientFunds() {
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.LOCKED, 120_000L, null, now));

        // The money is there. The bank refuses anyway, which is what its version check does when a
        // second settlement lands on a debt the first has already cleared.
        IslandBank bank = new IslandBank(islandId, 200_000L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));
        when(mockBankPort.executeTransaction(
                        eq(islandId),
                        any(UUID.class),
                        eq("PRIMARY"),
                        eq(2),
                        eq(-120_000L),
                        anyString(),
                        eq("node-alpha"),
                        anyLong(),
                        eq(1L),
                        any(UUID.class),
                        anyString()))
                .thenAnswer(invocation -> {
                    // What the winner did before this caller got there.
                    storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.SOLVENT, 0L, null, now));
                    service.warmCache(java.util.List.of(
                            new IslandBankruptcyRecord(islandId, BankruptcyStatus.SOLVENT, 0L, null, now)));
                    return new BankTransactionOutcome.StaleVersion(1L, 2L);
                });

        BankruptcyRemediationResult result = service.settleArrears(islandId, now, nodeId);

        assertThat(result)
                .describedAs("a player who has just paid must not be told they cannot afford it")
                .isNotInstanceOf(BankruptcyRemediationResult.InsufficientFunds.class);
        assertThat(result).isInstanceOf(BankruptcyRemediationResult.NotInArrears.class);
    }

    @Test
    @DisplayName("A settlement the bank refused with the debt still standing is refused, not unaffordable")
    void aRefusedSettlementWithTheDebtStillThereSaysSo() {
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.LOCKED, 120_000L, null, now));

        IslandBank bank = new IslandBank(islandId, 200_000L, 0L, 0L, 1L, now);
        when(mockBankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));
        when(mockBankPort.executeTransaction(
                        eq(islandId),
                        any(UUID.class),
                        eq("PRIMARY"),
                        eq(2),
                        eq(-120_000L),
                        anyString(),
                        eq("node-alpha"),
                        anyLong(),
                        eq(1L),
                        any(UUID.class),
                        anyString()))
                .thenReturn(new BankTransactionOutcome.StaleVersion(1L, 2L));

        BankruptcyRemediationResult result = service.settleArrears(islandId, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyRemediationResult.PaymentRefused.class);
        BankruptcyRemediationResult.PaymentRefused refused = (BankruptcyRemediationResult.PaymentRefused) result;
        assertThat(refused.debtAmount()).isEqualTo(120_000L);
        assertThat(service.isIslandLocked(islandId, now))
                .describedAs("the island is still locked, because nothing was paid")
                .isTrue();
    }

    @Test
    @DisplayName("settleArrears returns NotInArrears when island is solvent")
    void settleArrearsReturnsNotInArrearsWhenSolvent() {
        BankruptcyRemediationResult result = service.settleArrears(islandId, now, nodeId);

        assertThat(result).isInstanceOf(BankruptcyRemediationResult.NotInArrears.class);
    }

    @Test
    @DisplayName("deleteIslandBankruptcy deletes record from storage")
    void deleteIslandBankruptcyDeletesFromStorage() {
        storage.save(new IslandBankruptcyRecord(islandId, BankruptcyStatus.LOCKED, 100_000L, null, now));

        service.deleteIslandBankruptcy(islandId);

        assertThat(storage.findByIslandId(islandId)).isEmpty();
    }

    private static class InMemoryBankruptcyStorage implements IslandBankruptcyStoragePort {
        private final Map<IslandId, IslandBankruptcyRecord> records = new HashMap<>();

        @Override
        public Optional<IslandBankruptcyRecord> findByIslandId(IslandId islandId) {
            return Optional.ofNullable(records.get(islandId));
        }

        @Override
        public void save(IslandBankruptcyRecord record) {
            records.put(record.islandId(), record);
        }

        @Override
        public java.util.List<IslandBankruptcyRecord> findAllBankruptcies() {
            return records.values().stream()
                    .filter(r -> r.status() != BankruptcyStatus.SOLVENT || r.debtMinorUnits() > 0)
                    .toList();
        }

        @Override
        public void deleteByIslandId(IslandId islandId) {
            records.remove(islandId);
        }
    }
}
