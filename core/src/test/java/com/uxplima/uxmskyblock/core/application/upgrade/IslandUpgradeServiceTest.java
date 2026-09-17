package com.uxplima.uxmskyblock.core.application.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandUpgradeServiceTest {

    private FakeUpgradeStorage storage;
    private FakeBankPort bankPort;
    private IslandUpgradeService service;

    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final UUID actorUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        storage = new FakeUpgradeStorage();
        bankPort = new FakeBankPort();

        UpgradeDefinition sizeDef = new UpgradeDefinition(
                UpgradeId.SIZE,
                "Island Size",
                List.of(
                        new UpgradeTier(1, 10_000L, "PRIMARY", Map.of("radius", 150.0)),
                        new UpgradeTier(2, 25_000L, "PRIMARY", Map.of("radius", 200.0))));

        service = new IslandUpgradeService(storage, Map.of(UpgradeId.SIZE, sizeDef));
    }

    @Test
    @DisplayName("purchaseUpgrade advances tier and charges bank account")
    void purchaseUpgradeSuccess() {
        bankPort.balance = 50_000L;

        UpgradePurchaseOutcome outcome =
                service.purchaseUpgrade(islandId, UpgradeId.SIZE, actorUuid, bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.Success.class);
        UpgradePurchaseOutcome.Success s = (UpgradePurchaseOutcome.Success) outcome;
        assertThat(s.newTier()).isEqualTo(1);
        assertThat(s.costPaid()).isEqualTo(10_000L);
        assertThat(storage.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(1);
        assertThat(bankPort.balance).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("purchaseUpgrade fails when funds are insufficient")
    void purchaseInsufficientFunds() {
        bankPort.balance = 5_000L; // tier 1 requires 10,000

        UpgradePurchaseOutcome outcome =
                service.purchaseUpgrade(islandId, UpgradeId.SIZE, actorUuid, bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.InsufficientFunds.class);
        assertThat(storage.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(0);
    }

    @Test
    @DisplayName("purchaseUpgrade rejects when max tier is already reached")
    void maxTierReached() {
        bankPort.balance = 100_000L;
        storage.setUpgradeTier(islandId, UpgradeId.SIZE, 2);

        UpgradePurchaseOutcome outcome =
                service.purchaseUpgrade(islandId, UpgradeId.SIZE, actorUuid, bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.MaxTierReached.class);
    }

    private static class FakeUpgradeStorage implements IslandUpgradeStoragePort {
        private final Map<UpgradeId, Integer> tiers = new HashMap<>();

        @Override
        public int getUpgradeTier(IslandId id, UpgradeId upgradeId) {
            return tiers.getOrDefault(upgradeId, 0);
        }

        @Override
        public Map<UpgradeId, Integer> getUpgrades(IslandId id) {
            return Map.copyOf(tiers);
        }

        @Override
        public void setUpgradeTier(IslandId id, UpgradeId upgradeId, int tier) {
            tiers.put(upgradeId, tier);
        }
    }

    private static class FakeBankPort implements IslandBankPort {
        long balance = 0L;
        long version = 1L;

        @Override
        public Optional<IslandBank> findBankByIslandId(IslandId id) {
            return Optional.of(new IslandBank(id, balance, 0L, 0L, version, Instant.now()));
        }

        @Override
        public IslandBank createBank(IslandId id) {
            return new IslandBank(id, balance, 0L, 0L, version, Instant.now());
        }

        @Override
        public BankTransactionOutcome executeTransaction(
                IslandId id,
                UUID actor,
                String curr,
                int scale,
                long delta,
                String reason,
                String node,
                long epoch,
                long expVer,
                UUID opId,
                String key) {

            if (balance + delta < 0) {
                return new BankTransactionOutcome.InsufficientFunds(balance, delta);
            }
            balance += delta;
            version++;
            IslandBank updated = new IslandBank(id, balance, 0L, 0L, version, Instant.now());
            BankTransaction tx = new BankTransaction(
                    UUID.randomUUID(), opId, id, actor, curr, scale, delta, balance, reason, Instant.now());
            return new BankTransactionOutcome.Success(updated, tx);
        }

        @Override
        public List<BankTransaction> getTransactionHistory(IslandId id, int limit) {
            return List.of();
        }
    }
}
