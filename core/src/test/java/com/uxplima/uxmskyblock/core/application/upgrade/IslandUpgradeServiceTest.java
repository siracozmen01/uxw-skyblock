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
    @DisplayName("A purchase that loses the race to another is refused and its charge is put back")
    void aLostRaceIsRefunded() {
        bankPort.balance = 100_000L;
        IslandUpgradeService racing = new IslandUpgradeService(new RaceLosingStorage(), service.definitions());

        UpgradePurchaseOutcome outcome =
                racing.purchaseUpgrade(islandId, UpgradeId.SIZE, UUID.randomUUID(), bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.PaymentFailed.class);
        assertThat(bankPort.balance)
                .describedAs("the cost was taken and given straight back")
                .isEqualTo(100_000L);
    }

    @Test
    @DisplayName("A store that charges and moves together takes the whole purchase, and nothing is charged apart")
    void aStoreThatDoesBothTakesThePurchase() {
        bankPort.balance = 50_000L;
        OneTransactionStorage together = new OneTransactionStorage(false);
        IslandUpgradeService atomic = new IslandUpgradeService(together, service.definitions());

        UpgradePurchaseOutcome outcome =
                atomic.purchaseUpgrade(islandId, UpgradeId.SIZE, actorUuid, bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.Success.class);
        assertThat(together.asked).hasSize(1);
        TierPurchase asked = together.asked.get(0);
        assertThat(asked.fromTier()).isZero();
        assertThat(asked.toTier()).isEqualTo(1);
        assertThat(asked.costMinorUnits()).isEqualTo(10_000L);
        assertThat(together.getUpgradeTier(islandId, UpgradeId.SIZE)).isEqualTo(1);
        assertThat(bankPort.balance)
                .describedAs("the store charged it; a second charge here would take it twice")
                .isEqualTo(50_000L);
    }

    @Test
    @DisplayName("A purchase the store says lost the race is refused with nothing charged and nothing refunded")
    void aRaceTheStoreRolledBackIsRefused() {
        bankPort.balance = 50_000L;
        IslandUpgradeService atomic = new IslandUpgradeService(new OneTransactionStorage(true), service.definitions());

        UpgradePurchaseOutcome outcome =
                atomic.purchaseUpgrade(islandId, UpgradeId.SIZE, actorUuid, bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.PaymentFailed.class);
        assertThat(((UpgradePurchaseOutcome.PaymentFailed) outcome).kind())
                .isEqualTo(UpgradePurchaseOutcome.PaymentFailed.Kind.RACED);
        assertThat(bankPort.balance).isEqualTo(50_000L);
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

    @Test
    @DisplayName(
            "A first tier that costs nothing is held from the start, so a purchase sells the first one with a price")
    void aFreeBaseTierIsNotSold() {
        bankPort.balance = 50_000L;
        UpgradeDefinition members = new UpgradeDefinition(
                UpgradeId.MEMBERS,
                "Members",
                List.of(
                        new UpgradeTier(1, 0L, "PRIMARY", Map.of("max_members", 4.0)),
                        new UpgradeTier(2, 20_000L, "PRIMARY", Map.of("max_members", 8.0))));
        IslandUpgradeService withBase = new IslandUpgradeService(storage, Map.of(UpgradeId.MEMBERS, members));

        UpgradePurchaseOutcome outcome =
                withBase.purchaseUpgrade(islandId, UpgradeId.MEMBERS, actorUuid, bankPort, "node-1", 1L);

        assertThat(outcome).isInstanceOf(UpgradePurchaseOutcome.Success.class);
        UpgradePurchaseOutcome.Success bought = (UpgradePurchaseOutcome.Success) outcome;
        assertThat(bought.newTier()).isEqualTo(2);
        assertThat(bought.costPaid()).isEqualTo(20_000L);
        assertThat(storage.getUpgradeTier(islandId, UpgradeId.MEMBERS)).isEqualTo(2);
        assertThat(bankPort.balance).isEqualTo(30_000L);
        assertThat(withBase.purchaseUpgrade(islandId, UpgradeId.MEMBERS, actorUuid, bankPort, "node-1", 1L))
                .isInstanceOf(UpgradePurchaseOutcome.MaxTierReached.class);
    }

    @Test
    @DisplayName("An island stands on a free first tier it never bought, and on whatever tier it did buy")
    void standingReadsTheFreeBaseTier() {
        UpgradeDefinition members = new UpgradeDefinition(
                UpgradeId.MEMBERS,
                "Members",
                List.of(
                        new UpgradeTier(1, 0L, "PRIMARY", Map.of("max_members", 4.0)),
                        new UpgradeTier(2, 20_000L, "PRIMARY", Map.of("max_members", 8.0))));
        IslandUpgradeService both = new IslandUpgradeService(
                storage, Map.of(UpgradeId.SIZE, service.definitions().get(UpgradeId.SIZE), UpgradeId.MEMBERS, members));

        assertThat(both.standing(Map.of()))
                .describedAs("nothing bought: the free first tier is held, a priced one is not")
                .containsEntry(UpgradeId.MEMBERS, 1)
                .containsEntry(UpgradeId.SIZE, 0);
        assertThat(both.standing(Map.of(UpgradeId.MEMBERS, 2, UpgradeId.SIZE, 1)))
                .containsEntry(UpgradeId.MEMBERS, 2)
                .containsEntry(UpgradeId.SIZE, 1);
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
        public boolean compareAndSetUpgradeTier(IslandId id, UpgradeId upgradeId, int expectedTier, int newTier) {
            if (getUpgradeTier(id, upgradeId) != expectedTier) {
                return false;
            }
            setUpgradeTier(id, upgradeId, newTier);
            return true;
        }

        @Override
        public void setUpgradeTier(IslandId id, UpgradeId upgradeId, int tier) {
            tiers.put(upgradeId, tier);
        }
    }

    /** A storage whose tier was moved by somebody else between the read and the write. */
    private static final class RaceLosingStorage extends FakeUpgradeStorage {
        @Override
        public boolean compareAndSetUpgradeTier(IslandId id, UpgradeId upgradeId, int expectedTier, int newTier) {
            return false;
        }
    }

    /** A store that charges and moves the tier in one transaction, as the SQL one does. */
    private static final class OneTransactionStorage extends FakeUpgradeStorage {
        private final boolean loses;
        private final List<TierPurchase> asked = new java.util.ArrayList<>();

        OneTransactionStorage(boolean loses) {
            this.loses = loses;
        }

        @Override
        public Optional<PaidTierMove> chargeAndMoveTier(TierPurchase purchase) {
            asked.add(purchase);
            if (loses) {
                return Optional.of(new PaidTierMove.Raced());
            }
            setUpgradeTier(purchase.islandId(), purchase.upgradeId(), purchase.toTier());
            IslandBank after = new IslandBank(purchase.islandId(), 0L, 0L, 0L, 2L, Instant.now());
            BankTransaction charge = new BankTransaction(
                    UUID.randomUUID(),
                    purchase.operationId(),
                    purchase.islandId(),
                    purchase.actorUuid(),
                    purchase.currencyId(),
                    2,
                    -purchase.costMinorUnits(),
                    0L,
                    purchase.reason(),
                    Instant.now());
            return Optional.of(new PaidTierMove.Moved(new BankTransactionOutcome.Success(after, charge)));
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
