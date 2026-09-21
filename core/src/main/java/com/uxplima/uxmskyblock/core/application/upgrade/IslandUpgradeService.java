package com.uxplima.uxmskyblock.core.application.upgrade;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradePurchaseOutcome;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import org.jspecify.annotations.Nullable;

/**
 * Domain service coordinating upgrade progression, tier verification, and financial settlement.
 */
public final class IslandUpgradeService {

    private static final Logger LOGGER = Logger.getLogger(IslandUpgradeService.class.getName());

    private final IslandUpgradeStoragePort storagePort;
    private final Map<UpgradeId, UpgradeDefinition> definitions;
    private final java.util.concurrent.ConcurrentMap<IslandId, java.util.concurrent.ConcurrentMap<UpgradeId, Integer>>
            tierCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<IslandId> loadingIslands =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final @Nullable IslandBankPort bankPort;
    private final @Nullable IslandAuthorityPort authorityPort;

    public IslandUpgradeService(IslandUpgradeStoragePort storagePort, Map<UpgradeId, UpgradeDefinition> definitions) {
        this(storagePort, definitions, null, null);
    }

    /**
     * The canonical constructor, carrying what a purchase needs beyond the tiers themselves.
     *
     * <p>A purchase charges the island bank, and the bank refuses a write from a node that does not
     * hold the island's authority lease. A caller that had to find the lease itself would be every
     * command, every menu and every API bridge doing the same three lines, so the service does it,
     * the way the bank service already does.
     */
    public IslandUpgradeService(
            IslandUpgradeStoragePort storagePort,
            Map<UpgradeId, UpgradeDefinition> definitions,
            @Nullable IslandBankPort bankPort,
            @Nullable IslandAuthorityPort authorityPort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort");
        this.definitions = (definitions == null) ? Map.of() : Map.copyOf(definitions);
        this.bankPort = bankPort;
        this.authorityPort = authorityPort;
    }

    /** Whether this service was built with what a purchase needs. */
    public boolean canPurchase() {
        return bankPort != null && authorityPort != null;
    }

    /**
     * Buys the next tier, finding the bank and the authority lease itself.
     *
     * <p>Refuses when this node does not hold the lease, or holds an expired one, rather than
     * charging into a write the bank would reject with less to say about why.
     */
    public UpgradePurchaseOutcome purchaseUpgrade(
            IslandId islandId, UpgradeId upgradeId, UUID actorUuid, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(serverNodeId, "serverNodeId");
        if (bankPort == null || authorityPort == null) {
            return new UpgradePurchaseOutcome.PaymentFailed("Upgrades cannot be bought on this server.");
        }

        Optional<IslandAuthorityRecord> optAuthority = authorityPort.findAuthority(islandId);
        if (optAuthority.isEmpty()) {
            return new UpgradePurchaseOutcome.PaymentFailed("No authority record found for island " + islandId);
        }
        IslandAuthorityRecord authority = optAuthority.get();
        if (!authority.authoritativeNode().equals(serverNodeId)) {
            return new UpgradePurchaseOutcome.PaymentFailed("This server does not hold authority for island " + islandId
                    + " (held by " + authority.authoritativeNode() + ")");
        }
        if (authority.leaseExpiresAt().isBefore(java.time.Instant.now())) {
            return new UpgradePurchaseOutcome.PaymentFailed(
                    "Authority lease expired at " + authority.leaseExpiresAt() + " for island " + islandId);
        }

        return purchaseUpgrade(
                islandId, upgradeId, actorUuid, bankPort, serverNodeId.value(), authority.authorityEpoch());
    }

    /** The catalogue this service was built with, so a caller can build a second one over it. */
    public Map<UpgradeId, UpgradeDefinition> definitions() {
        return definitions;
    }

    public Optional<UpgradeDefinition> getDefinition(UpgradeId upgradeId) {
        return Optional.ofNullable(definitions.get(upgradeId));
    }

    public boolean isCached(IslandId islandId) {
        return tierCache.containsKey(islandId);
    }

    public int getCachedTier(IslandId islandId, UpgradeId upgradeId) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        java.util.concurrent.ConcurrentMap<UpgradeId, Integer> islandTiers = tierCache.get(islandId);
        if (islandTiers != null) {
            Integer cached = islandTiers.get(upgradeId);
            if (cached != null) {
                return cached;
            }
        }
        return 0;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void refreshCacheAsync(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        if (loadingIslands.add(islandId)) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    warmCache(islandId);
                } finally {
                    loadingIslands.remove(islandId);
                }
            });
        }
    }

    public void warmCache(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        Map<UpgradeId, Integer> upgrades = storagePort.getUpgrades(islandId);
        tierCache.put(islandId, new java.util.concurrent.ConcurrentHashMap<>(upgrades));
    }

    public void invalidateCache(IslandId islandId) {
        tierCache.remove(islandId);
    }

    public int getCurrentTier(IslandId islandId, UpgradeId upgradeId) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        java.util.concurrent.ConcurrentMap<UpgradeId, Integer> islandTiers = tierCache.get(islandId);
        if (islandTiers != null) {
            Integer cached = islandTiers.get(upgradeId);
            if (cached != null) {
                return cached;
            }
        }
        int tier = storagePort.getUpgradeTier(islandId, upgradeId);
        tierCache
                .computeIfAbsent(islandId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(upgradeId, tier);
        return tier;
    }

    public Map<UpgradeId, Integer> getAllUpgrades(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId");
        return storagePort.getUpgrades(islandId);
    }

    /**
     * Attempts to purchase the next tier of the specified upgrade.
     *
     * @param islandId target island ID
     * @param upgradeId target upgrade identifier
     * @param actorUuid player UUID making the purchase
     * @param bankPort island bank application port for settlement
     * @param currentNode cluster node asserting authority
     * @param expectedEpoch authority lease epoch
     * @return purchase outcome
     */
    public UpgradePurchaseOutcome purchaseUpgrade(
            IslandId islandId,
            UpgradeId upgradeId,
            UUID actorUuid,
            IslandBankPort bankPort,
            String currentNode,
            long expectedEpoch) {

        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(upgradeId, "upgradeId");
        Objects.requireNonNull(actorUuid, "actorUuid");
        Objects.requireNonNull(bankPort, "bankPort");
        Objects.requireNonNull(currentNode, "currentNode");

        UpgradeDefinition definition = definitions.get(upgradeId);
        if (definition == null) {
            return new UpgradePurchaseOutcome.UpgradeNotFound(upgradeId);
        }

        int currentTier = storagePort.getUpgradeTier(islandId, upgradeId);
        if (currentTier >= definition.maxTier()) {
            return new UpgradePurchaseOutcome.MaxTierReached(upgradeId, currentTier);
        }

        int nextTierNum = currentTier + 1;
        UpgradeTier nextTier = definition.getTier(nextTierNum).orElseThrow();

        boolean charged = false;
        if (nextTier.costMinorUnits() > 0) {
            IslandBank bank = bankPort.findBankByIslandId(islandId).orElseGet(() -> bankPort.createBank(islandId));

            UUID operationId = UUID.randomUUID();
            String idempotencyKey = "upg-" + upgradeId.key() + "-" + nextTierNum + "-" + operationId;

            BankTransactionOutcome bankOutcome = bankPort.executeTransaction(
                    islandId,
                    actorUuid,
                    nextTier.currencyId(),
                    2,
                    -nextTier.costMinorUnits(),
                    "Upgrade " + upgradeId.key() + " to tier " + nextTierNum,
                    currentNode,
                    expectedEpoch,
                    bank.version(),
                    operationId,
                    idempotencyKey);

            if (bankOutcome instanceof BankTransactionOutcome.InsufficientFunds rej) {
                return new UpgradePurchaseOutcome.InsufficientFunds(nextTier.costMinorUnits(), rej.currentBalance());
            } else if (!(bankOutcome instanceof BankTransactionOutcome.Success)) {
                return new UpgradePurchaseOutcome.PaymentFailed("Bank transaction failed: " + bankOutcome);
            }
            charged = true;
        }

        // The tier moves only from the one that was read before the bank was charged. Without that
        // condition two purchases can read the same tier, both pay, and both write the next one:
        // the island pays twice and moves once.
        boolean applied = storagePort.compareAndSetUpgradeTier(islandId, upgradeId, currentTier, nextTierNum);
        if (!applied) {
            if (charged) {
                refund(islandId, upgradeId, actorUuid, bankPort, currentNode, expectedEpoch, nextTier, nextTierNum);
            }
            invalidateCache(islandId);
            return new UpgradePurchaseOutcome.PaymentFailed(
                    "Another purchase moved this upgrade first. Nothing was charged.");
        }
        tierCache
                .computeIfAbsent(islandId, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(upgradeId, nextTierNum);
        return new UpgradePurchaseOutcome.Success(upgradeId, nextTierNum, nextTier.costMinorUnits());
    }

    /**
     * Puts the cost back when the tier could not be moved after the bank was charged. A refund that
     * itself fails is logged with everything needed to settle it by hand: the alternative is money
     * leaving an island with nothing to show for it and nobody knowing.
     */
    private void refund(
            IslandId islandId,
            UpgradeId upgradeId,
            UUID actorUuid,
            IslandBankPort bankPort,
            String currentNode,
            long expectedEpoch,
            UpgradeTier tier,
            int attemptedTier) {
        try {
            IslandBank bank = bankPort.findBankByIslandId(islandId).orElseGet(() -> bankPort.createBank(islandId));
            UUID operationId = UUID.randomUUID();
            BankTransactionOutcome outcome = bankPort.executeTransaction(
                    islandId,
                    actorUuid,
                    tier.currencyId(),
                    2,
                    tier.costMinorUnits(),
                    "Refund for upgrade " + upgradeId.key() + " tier " + attemptedTier,
                    currentNode,
                    expectedEpoch,
                    bank.version(),
                    operationId,
                    "upg-refund-" + upgradeId.key() + "-" + attemptedTier + "-" + operationId);
            if (!(outcome instanceof BankTransactionOutcome.Success)) {
                LOGGER.log(
                        Level.SEVERE,
                        "Upgrade refund was refused. island={0} upgrade={1} tier={2} amount={3} outcome={4}",
                        new Object[] {islandId, upgradeId.key(), attemptedTier, tier.costMinorUnits(), outcome});
            }
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.SEVERE,
                    "Upgrade refund threw. island=" + islandId + " upgrade=" + upgradeId.key() + " amount="
                            + tier.costMinorUnits(),
                    e);
        }
    }
}
