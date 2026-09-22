package com.uxplima.uxmskyblock.core.application.bank;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyCycleResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyRemediationResult;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyStatus;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Core application service managing island upkeep maintenance, two-stage bankruptcy lifecycles
 * (GRACE and LOCKED), debt accumulation, and instant atomic remediation (Section 2.39).
 */
public final class IslandBankruptcyService {

    public static final UUID SYSTEM_UPKEEP_ACTOR =
            UUID.nameUUIDFromBytes("island-upkeep-actor".getBytes(StandardCharsets.UTF_8));

    private final IslandBankruptcyStoragePort bankruptcyStoragePort;
    private final IslandBankPort bankPort;
    private final IslandAuthorityPort authorityPort;
    private final Supplier<IslandUpkeepPolicy> policySupplier;
    private final ConcurrentMap<IslandId, IslandBankruptcyRecord> bankruptcyCache = new ConcurrentHashMap<>();

    /**
     * How long an authority lease this service takes runs, when the caller names no number.
     *
     * <p>It used to be a day written here and nothing renewed it. The heartbeat renews it now and
     * the operator sets the length, so this is only the fallback.
     */
    public static final int DEFAULT_AUTHORITY_LEASE_SECONDS = 600;

    private final int authorityLeaseSeconds;

    public IslandBankruptcyService(
            IslandBankruptcyStoragePort bankruptcyStoragePort,
            IslandBankPort bankPort,
            IslandAuthorityPort authorityPort,
            Supplier<IslandUpkeepPolicy> policySupplier) {
        this(bankruptcyStoragePort, bankPort, authorityPort, policySupplier, DEFAULT_AUTHORITY_LEASE_SECONDS);
    }

    /** The canonical constructor, carrying how long an authority lease it takes runs. */
    public IslandBankruptcyService(
            IslandBankruptcyStoragePort bankruptcyStoragePort,
            IslandBankPort bankPort,
            IslandAuthorityPort authorityPort,
            Supplier<IslandUpkeepPolicy> policySupplier,
            int authorityLeaseSeconds) {
        if (authorityLeaseSeconds < 1) {
            throw new IllegalArgumentException("authorityLeaseSeconds must be >= 1: " + authorityLeaseSeconds);
        }
        this.authorityLeaseSeconds = authorityLeaseSeconds;
        this.bankruptcyStoragePort =
                Objects.requireNonNull(bankruptcyStoragePort, "bankruptcyStoragePort must not be null");
        this.bankPort = Objects.requireNonNull(bankPort, "bankPort must not be null");
        this.authorityPort = Objects.requireNonNull(authorityPort, "authorityPort must not be null");
        this.policySupplier = Objects.requireNonNull(policySupplier, "policySupplier must not be null");

        try {
            List<IslandBankruptcyRecord> active = this.bankruptcyStoragePort.findAllBankruptcies();
            warmCache(active);
        } catch (Exception ignored) {
            // Storage port may not be ready or mocked without default returns
        }
    }

    /**
     * Pre-populates the in-memory bankruptcy cache to ensure zero relational DB I/O on hot paths.
     */
    public void warmCache(Collection<IslandBankruptcyRecord> records) {
        if (records != null) {
            for (IslandBankruptcyRecord r : records) {
                if (r != null) {
                    bankruptcyCache.put(r.islandId(), r);
                }
            }
        }
    }

    /**
     * Evaluates and executes an upkeep debit cycle for the specified island.
     *
     * @param islandId target island identity
     * @param memberCount active registered member count for per-member fee scaling
     * @param now current evaluation timestamp
     * @param serverNodeId local cluster node asserting authority
     * @return typed outcome of the upkeep cycle
     */
    public BankruptcyCycleResult processUpkeepCycle(
            IslandId islandId, int memberCount, Instant now, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");

        IslandUpkeepPolicy policy = policySupplier.get();
        if (!policy.enabled()) {
            return new BankruptcyCycleResult.SkippedDisabled();
        }

        long fee = policy.calculateUpkeepFee(memberCount);
        Optional<IslandBank> optBank = bankPort.findBankByIslandId(islandId);
        long currentBalance = optBank.map(IslandBank::primaryBalanceMinorUnits).orElse(0L);

        IslandBankruptcyRecord record = getBankruptcyRecord(islandId, now);

        if (currentBalance >= fee) {
            // Sufficient funds: debit upkeep fee
            long epoch = resolveAuthorityEpoch(islandId, serverNodeId);
            long expectedVersion = optBank.map(IslandBank::version).orElse(1L);
            UUID opId = UUID.randomUUID();
            String idempotencyKey = "upkeep-" + opId;

            BankTransactionOutcome outcome = bankPort.executeTransaction(
                    islandId,
                    SYSTEM_UPKEEP_ACTOR,
                    "PRIMARY",
                    2,
                    -fee,
                    "Automated island upkeep fee",
                    serverNodeId.value(),
                    epoch,
                    expectedVersion,
                    opId,
                    idempotencyKey);

            if (outcome instanceof BankTransactionOutcome.Success success) {
                if (record.status() != BankruptcyStatus.SOLVENT || record.debtMinorUnits() > 0) {
                    // Recover back to solvent if arrears were zero
                    if (record.debtMinorUnits() == 0) {
                        IslandBankruptcyRecord solvent = record.toSolvent(now);
                        bankruptcyStoragePort.save(solvent);
                        bankruptcyCache.put(islandId, solvent);
                    }
                }
                return new BankruptcyCycleResult.Paid(fee, success.updatedBank().primaryBalanceMinorUnits());
            }
        }

        // Insufficient funds: apply two-stage failure escalation
        if (record.status() == BankruptcyStatus.SOLVENT) {
            Instant graceDeadline = now.plus(policy.graceDuration());
            IslandBankruptcyRecord updated = record.toGrace(fee, graceDeadline, now);
            bankruptcyStoragePort.save(updated);
            bankruptcyCache.put(islandId, updated);
            return new BankruptcyCycleResult.GraceEntered(fee, graceDeadline, fee);
        } else if (record.status() == BankruptcyStatus.GRACE) {
            Instant graceUntil = record.graceUntil() != null ? record.graceUntil() : now.plus(policy.graceDuration());
            if (!now.isBefore(graceUntil)) {
                // Grace expired: escalate to quarantine lockout
                IslandBankruptcyRecord updated = record.addDebt(fee, now).toLocked(now);
                bankruptcyStoragePort.save(updated);
                bankruptcyCache.put(islandId, updated);
                return new BankruptcyCycleResult.LockoutApplied(fee, updated.debtMinorUnits());
            } else {
                // Still in grace: accumulate debt
                IslandBankruptcyRecord updated = record.addDebt(fee, now);
                bankruptcyStoragePort.save(updated);
                bankruptcyCache.put(islandId, updated);
                return new BankruptcyCycleResult.GraceExtended(fee, graceUntil, updated.debtMinorUnits());
            }
        } else {
            // Already locked: accumulate additional debt
            IslandBankruptcyRecord updated = record.addDebt(fee, now);
            bankruptcyStoragePort.save(updated);
            bankruptcyCache.put(islandId, updated);
            return new BankruptcyCycleResult.LockoutApplied(fee, updated.debtMinorUnits());
        }
    }

    /**
     * Attempts to settle outstanding arrears and instantly remediate bankruptcy status.
     *
     * @param islandId target island identity
     * @param now current evaluation timestamp
     * @param serverNodeId local cluster node asserting authority
     * @return typed outcome of the remediation attempt
     */
    public BankruptcyRemediationResult settleArrears(IslandId islandId, Instant now, ServerNodeId serverNodeId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");

        IslandBankruptcyRecord record = getBankruptcyRecord(islandId, now);
        if (record.debtMinorUnits() == 0 && record.status() == BankruptcyStatus.SOLVENT) {
            return new BankruptcyRemediationResult.NotInArrears();
        }

        long debt = record.debtMinorUnits();
        Optional<IslandBank> optBank = bankPort.findBankByIslandId(islandId);
        long currentBalance = optBank.map(IslandBank::primaryBalanceMinorUnits).orElse(0L);

        if (currentBalance < debt) {
            return new BankruptcyRemediationResult.InsufficientFunds(debt, currentBalance);
        }

        long epoch = resolveAuthorityEpoch(islandId, serverNodeId);
        long expectedVersion = optBank.map(IslandBank::version).orElse(1L);
        UUID opId = UUID.randomUUID();
        String idempotencyKey = "settle-debt-" + opId;

        BankTransactionOutcome outcome = bankPort.executeTransaction(
                islandId,
                SYSTEM_UPKEEP_ACTOR,
                "PRIMARY",
                2,
                -debt,
                "Settlement of island upkeep arrears",
                serverNodeId.value(),
                epoch,
                expectedVersion,
                opId,
                idempotencyKey);

        if (outcome instanceof BankTransactionOutcome.Success success) {
            IslandBankruptcyRecord solvent = record.toSolvent(now);
            bankruptcyStoragePort.save(solvent);
            bankruptcyCache.put(islandId, solvent);
            return new BankruptcyRemediationResult.Settled(
                    debt, success.updatedBank().primaryBalanceMinorUnits());
        }

        if (outcome instanceof BankTransactionOutcome.InsufficientFunds shortfall) {
            return new BankruptcyRemediationResult.InsufficientFunds(debt, shortfall.currentBalance());
        }

        // The balance was enough when it was read, so a refusal here is the bank's optimistic
        // version check: somebody else wrote to this bank in between, and on a debt that usually
        // means they settled it. Telling the player they cannot afford what they have just paid for
        // is the one answer that is certainly wrong, so the debt is read again and the truth is
        // whatever it says now.
        IslandBankruptcyRecord after = getBankruptcyRecord(islandId, now);
        if (after.debtMinorUnits() == 0 && after.status() == BankruptcyStatus.SOLVENT) {
            return new BankruptcyRemediationResult.NotInArrears();
        }
        return new BankruptcyRemediationResult.PaymentRefused(debt, String.valueOf(outcome));
    }

    /**
     * Returns true if the island is currently under quarantine lockout due to expired bankruptcy grace.
     * Hits in-memory cache to guarantee zero relational DB queries on hot paths.
     */
    public boolean isIslandLocked(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        IslandBankruptcyRecord cached = bankruptcyCache.get(islandId);
        if (cached != null) {
            if (cached.status() == BankruptcyStatus.SOLVENT) {
                return false;
            }
            if (cached.status() == BankruptcyStatus.GRACE && cached.isLockoutActive(now)) {
                IslandBankruptcyRecord locked = cached.toLocked(now);
                bankruptcyCache.put(islandId, locked);
                bankruptcyStoragePort.save(locked);
                return true;
            }
            return cached.isLockoutActive(now);
        }
        return getBankruptcyRecord(islandId, now).isLockoutActive(now);
    }

    /**
     * Retrieves the current bankruptcy record for an island, automatically checking whether
     * an unexpired grace window has matured into quarantine lockout.
     */
    public IslandBankruptcyRecord getBankruptcyRecord(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        IslandBankruptcyRecord cached = bankruptcyCache.get(islandId);
        if (cached != null) {
            if (cached.status() == BankruptcyStatus.GRACE && cached.isLockoutActive(now)) {
                // Grace expired: persist transition to LOCKED
                IslandBankruptcyRecord locked = cached.toLocked(now);
                bankruptcyCache.put(islandId, locked);
                bankruptcyStoragePort.save(locked);
                return locked;
            }
            return cached;
        }

        Optional<IslandBankruptcyRecord> optRecord = bankruptcyStoragePort.findByIslandId(islandId);
        if (optRecord.isEmpty()) {
            IslandBankruptcyRecord solvent = IslandBankruptcyRecord.solvent(islandId, now);
            bankruptcyCache.put(islandId, solvent);
            return solvent;
        }

        IslandBankruptcyRecord record = optRecord.get();
        if (record.status() == BankruptcyStatus.GRACE && record.isLockoutActive(now)) {
            // Grace expired: persist transition to LOCKED
            IslandBankruptcyRecord locked = record.toLocked(now);
            bankruptcyCache.put(islandId, locked);
            bankruptcyStoragePort.save(locked);
            return locked;
        }

        bankruptcyCache.put(islandId, record);
        return record;
    }

    /**
     * Returns all active bankruptcies (either in GRACE or LOCKED) across the network.
     */
    public List<IslandBankruptcyRecord> getActiveBankruptcies() {
        return bankruptcyStoragePort.findAllBankruptcies();
    }

    /**
     * Lets go of everything remembered about an island, and of its row.
     *
     * <p>A player walking onto a healthy island puts a record saying so into the cache, so the cache
     * holds an entry for every island anybody has visited and nothing removed one. An island id is a
     * fresh uuid every time, so an erased island's entry is never read again.
     */
    public void forgetIsland(IslandId islandId) {
        deleteIslandBankruptcy(islandId);
    }

    /** How many islands this node is holding a record for, for a caller that wants to say so. */
    public int islandsHeldInMemory() {
        return bankruptcyCache.size();
    }

    /** Cleans up bankruptcy persistence when an island is deleted or reset. */
    public void deleteIslandBankruptcy(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        bankruptcyStoragePort.deleteByIslandId(islandId);
        bankruptcyCache.remove(islandId);
    }

    public IslandUpkeepPolicy policy() {
        return policySupplier.get();
    }

    private long resolveAuthorityEpoch(IslandId islandId, ServerNodeId serverNodeId) {
        Optional<IslandAuthorityRecord> optAuth = authorityPort.findAuthority(islandId);
        if (optAuth.isPresent()) {
            return optAuth.get().authorityEpoch();
        }
        IslandAuthorityOutcome outcome = authorityPort.acquireAuthority(islandId, serverNodeId, authorityLeaseSeconds);
        if (outcome instanceof IslandAuthorityOutcome.Success s) {
            return s.epoch();
        }
        return 1L;
    }
}
