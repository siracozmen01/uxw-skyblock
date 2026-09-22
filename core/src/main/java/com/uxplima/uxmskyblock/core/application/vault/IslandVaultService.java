package com.uxplima.uxmskyblock.core.application.vault;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import com.uxplima.uxmskyblock.core.domain.vault.DualSlotRecoveryAction;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.StaleVaultSessionException;
import com.uxplima.uxmskyblock.core.domain.vault.TransferSourceType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageBusyException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain service orchestrating shared island vault pages, pessimistic edit session leases,
 * write-ahead escrow transfers, dual-slot recovery, and rolling audit logs.
 */
public final class IslandVaultService {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandVaultService.class.getName());

    public static final int DEFAULT_BASE_PAGES = 1;
    public static final int DEFAULT_MAX_PAGES = 10;
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(60);

    private final IslandVaultStoragePort storagePort;
    private final @Nullable IslandUpgradeService upgradeService;
    private final int basePages;
    private final int maxPages;
    private final Duration defaultLeaseDuration;

    public IslandVaultService(
            IslandVaultStoragePort storagePort,
            @Nullable IslandUpgradeService upgradeService,
            int basePages,
            int maxPages,
            Duration defaultLeaseDuration) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.upgradeService = upgradeService;
        if (basePages < 1) {
            throw new IllegalArgumentException("basePages must be >= 1: " + basePages);
        }
        if (maxPages < basePages) {
            throw new IllegalArgumentException("maxPages must be >= basePages: " + maxPages);
        }
        this.basePages = basePages;
        this.maxPages = maxPages;
        this.defaultLeaseDuration =
                Objects.requireNonNull(defaultLeaseDuration, "defaultLeaseDuration must not be null");
    }

    public IslandVaultService(IslandVaultStoragePort storagePort, @Nullable IslandUpgradeService upgradeService) {
        this(storagePort, upgradeService, DEFAULT_BASE_PAGES, DEFAULT_MAX_PAGES, DEFAULT_LEASE_DURATION);
    }

    /**
     * Calculates the maximum number of unlocked vault pages for the given island.
     */
    public int getMaxAllowedPages(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (upgradeService == null) {
            return basePages;
        }

        int tier = upgradeService.getCurrentTier(islandId, UpgradeId.VAULT_PAGES);
        if (tier <= 0) {
            return basePages;
        }

        Optional<UpgradeDefinition> defOpt = upgradeService.getDefinition(UpgradeId.VAULT_PAGES);
        if (defOpt.isPresent()) {
            Optional<UpgradeTier> tierOpt = defOpt.get().getTier(tier);
            if (tierOpt.isPresent()) {
                Double limitProp = tierOpt.get().properties().get("limit");
                if (limitProp == null) {
                    limitProp = tierOpt.get().properties().get("pages");
                }
                if (limitProp != null) {
                    return Math.min(limitProp.intValue(), maxPages);
                }
            }
        }

        return Math.min(basePages + tier, maxPages);
    }

    /**
     * Result of opening a vault page for editing.
     */
    public record VaultOpenResult(VaultPage page, VaultEditSession session) {}

    /**
     * Attempts to acquire an exclusive pessimistic edit lease on a vault page.
     *
     * @param island island owning the vault
     * @param actorProfileId player profile attempting to open
     * @param role member's island role
     * @param page 1-based page index
     * @param playerUuid player UUID
     * @param leaseDuration duration of the lease
     * @return open result containing page and active edit session
     */
    public VaultOpenResult openVaultPage(
            Island island,
            ProfileId actorProfileId,
            IslandRole role,
            int page,
            UUID playerUuid,
            Duration leaseDuration) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Duration duration = (leaseDuration != null) ? leaseDuration : defaultLeaseDuration;

        if (!role.hasPermission(IslandPermission.VAULT_VIEW)) {
            throw new VaultPermissionDeniedException(island.id(), actorProfileId, IslandPermission.VAULT_VIEW);
        }

        int maxAllowed = getMaxAllowedPages(island.id());
        if (page < 1 || page > maxAllowed) {
            throw new VaultPageLimitExceededException(island.id(), page, maxAllowed);
        }

        // There has to be a row before there is a lease on it.
        if (storagePort.findPage(island.id(), page).isEmpty()) {
            var unused = storagePort.createPage(island.id(), page, new byte[0], actorProfileId.toString());
        }

        Optional<VaultEditSession> sessionOpt = storagePort.acquireEditSession(island.id(), page, playerUuid, duration);
        if (sessionOpt.isEmpty()) {
            throw busy(island, page, playerUuid, duration);
        }

        // The contents are read after the lease, never before it. Reading first opens a window: the
        // player who held the page commits between the read and the lease, and this window then
        // shows what the page held before that commit. Closing it writes those contents back, plus
        // whatever this player added, and the previous holder's deposit is erased or their
        // withdrawal is undone. Either way items appear or disappear, and the page itself is the
        // only record, so nothing says it happened.
        VaultPage vaultPage = storagePort
                .findPage(island.id(), page)
                .orElseThrow(() -> new IllegalStateException(
                        "The vault page " + page + " of island " + island.id() + " was leased and is not there"));

        return new VaultOpenResult(vaultPage, sessionOpt.get());
    }

    /** Who is holding the page and until when, for the refusal a waiting player reads. */
    private VaultPageBusyException busy(Island island, int page, UUID playerUuid, Duration duration) {
        Optional<VaultEditSession> currentActive = storagePort
                .findPage(island.id(), page)
                .map(VaultPage::activeSessionId)
                .flatMap(sessionId -> sessionId == null ? Optional.empty() : storagePort.findSession(sessionId));
        UUID activeEditor = currentActive.map(VaultEditSession::playerUuid).orElse(playerUuid);
        Instant expiresAt = currentActive
                .map(VaultEditSession::expiresAt)
                .orElseGet(() -> Instant.now().plus(duration));
        return new VaultPageBusyException(island.id(), page, activeEditor, expiresAt);
    }

    /**
     * Stages a durable write-ahead item transfer intent into escrow before visible slot mutation occurs.
     */
    public EscrowTransferRecord stageTransferIntent(
            VaultSessionId sessionId,
            TransferSourceType source,
            TransferSourceType destination,
            int sourceSlot,
            int destinationSlot,
            String sourceBeforeFingerprint,
            String sourceAfterFingerprint,
            String destinationBeforeFingerprint,
            String destinationAfterFingerprint,
            long sourceExpectedVersion,
            long destinationExpectedVersion,
            long sourceContainerVersion,
            long destinationContainerVersion,
            byte[] serializedItemNbt,
            int quantity) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        Objects.requireNonNull(sourceBeforeFingerprint, "sourceBeforeFingerprint must not be null");
        Objects.requireNonNull(sourceAfterFingerprint, "sourceAfterFingerprint must not be null");
        Objects.requireNonNull(destinationBeforeFingerprint, "destinationBeforeFingerprint must not be null");
        Objects.requireNonNull(destinationAfterFingerprint, "destinationAfterFingerprint must not be null");
        Objects.requireNonNull(serializedItemNbt, "serializedItemNbt must not be null");

        Instant now = Instant.now();
        EscrowTransferRecord record = new EscrowTransferRecord(
                UUID.randomUUID(),
                sessionId,
                source,
                destination,
                sourceSlot,
                destinationSlot,
                sourceBeforeFingerprint,
                sourceAfterFingerprint,
                destinationBeforeFingerprint,
                destinationAfterFingerprint,
                sourceExpectedVersion,
                destinationExpectedVersion,
                sourceContainerVersion,
                destinationContainerVersion,
                serializedItemNbt,
                quantity,
                EscrowTransferState.INTENT,
                now,
                now);

        storagePort.recordEscrowTransfer(record);
        return record;
    }

    /**
     * Marks an escrow transfer intent as applied in memory.
     */
    public void markTransferApplied(UUID transferId) {
        Objects.requireNonNull(transferId, "transferId must not be null");
        storagePort.updateEscrowTransferState(transferId, EscrowTransferState.APPLIED);
    }

    /**
     * Atomically commits a vault page and associated player inventory within a single atomic SQL transaction.
     */
    public void commitVaultPage(
            VaultSessionId sessionId,
            byte[] newContentsNbt,
            String modifiedBy,
            byte @Nullable [] playerInventoryNbt,
            @Nullable ProfileId playerProfileId,
            List<VaultAuditLogEntry> auditLogs) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(newContentsNbt, "newContentsNbt must not be null");
        Objects.requireNonNull(modifiedBy, "modifiedBy must not be null");

        boolean committed = storagePort.commitEditSession(
                sessionId, newContentsNbt, modifiedBy, playerInventoryNbt, playerProfileId);
        if (!committed) {
            throw new StaleVaultSessionException("Vault edit session " + sessionId
                    + " expired or was concurrently modified. Transaction rolled back.");
        }

        if (auditLogs != null) {
            for (VaultAuditLogEntry entry : auditLogs) {
                storagePort.appendAuditLog(entry);
            }
        }
    }

    /**
     * Aborts an active vault edit session, releasing the lock.
     */
    public boolean abortVaultPage(VaultSessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return storagePort.abortEditSession(sessionId);
    }

    /**
     * Closes the windows nobody is standing at any more.
     *
     * <p>A vault page is held under a lease, and a player who crashes or is disconnected mid edit
     * leaves the session ACTIVE and the page pointing at it. The query that finds those was written
     * and had no caller anywhere, so the rows piled up for as long as the server ran and every one
     * of them still named a page.
     *
     * <p>Taking the page over already worked, because acquiring a lease aborts an expired one on the
     * way past. What did not work was anybody doing it without a player asking: a page whose owner
     * never came back stayed pointed at a session that had ended, and nothing said so.
     *
     * @return how many windows were closed
     */
    public int closeExpiredSessions() {
        int closed = 0;
        for (VaultEditSession session : storagePort.findExpiredActiveSessions()) {
            try {
                if (storagePort.abortEditSession(session.sessionId())) {
                    closed++;
                }
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "Closing the expired vault session " + session.sessionId() + " failed. The rest are "
                                + "still tried.");
            }
        }
        if (closed > 0) {
            int total = closed;
            LOGGER.fine(() -> "Closed " + total + " vault sessions whose lease had run out.");
        }
        return closed;
    }

    /**
     * Evaluates uncommitted escrow transfer recovery according to the comprehensive Dual-Slot Decision Table:
     *
     * <pre>
     * | Source Observed | Dest Observed | Recovery Action       |
     * | BEFORE          | BEFORE        | ABORT                 |
     * | AFTER           | AFTER         | CONDITIONAL_ROLLBACK  |
     * | BEFORE          | AFTER         | RECOVERY_REQUIRED     | (Duplication hazard)
     * | AFTER           | BEFORE        | CONDITIONAL_ROLLBACK  | (Item loss hazard)
     * | UNKNOWN         | ANY           | RECOVERY_REQUIRED     | (Drift / tampering)
     * | ANY             | UNKNOWN       | RECOVERY_REQUIRED     | (Drift / tampering)
     * </pre>
     */
    public DualSlotRecoveryAction evaluateDualSlotRecovery(
            EscrowTransferRecord transfer, String observedSourceFingerprint, String observedDestFingerprint) {
        Objects.requireNonNull(transfer, "transfer must not be null");
        Objects.requireNonNull(observedSourceFingerprint, "observedSourceFingerprint must not be null");
        Objects.requireNonNull(observedDestFingerprint, "observedDestFingerprint must not be null");

        SlotState sourceState = classifySlotState(
                observedSourceFingerprint, transfer.sourceBeforeFingerprint(), transfer.sourceAfterFingerprint());

        SlotState destState = classifySlotState(
                observedDestFingerprint,
                transfer.destinationBeforeFingerprint(),
                transfer.destinationAfterFingerprint());

        if (sourceState == SlotState.UNKNOWN || destState == SlotState.UNKNOWN) {
            return DualSlotRecoveryAction.RECOVERY_REQUIRED;
        }

        if (sourceState == SlotState.BEFORE && destState == SlotState.BEFORE) {
            return DualSlotRecoveryAction.ABORT;
        }

        if (sourceState == SlotState.AFTER && destState == SlotState.AFTER) {
            return DualSlotRecoveryAction.CONDITIONAL_ROLLBACK;
        }

        if (sourceState == SlotState.BEFORE && destState == SlotState.AFTER) {
            // Duplication Hazard! Both slots contain item. Refuse repair; quarantine slot and vault page.
            return DualSlotRecoveryAction.RECOVERY_REQUIRED;
        }

        if (sourceState == SlotState.AFTER && destState == SlotState.BEFORE) {
            // Item Loss Hazard! Item cleared from source but missing at destination. Restore item to source.
            return DualSlotRecoveryAction.CONDITIONAL_ROLLBACK;
        }

        return DualSlotRecoveryAction.RECOVERY_REQUIRED;
    }

    private enum SlotState {
        BEFORE,
        AFTER,
        UNKNOWN
    }

    private SlotState classifySlotState(String observed, String beforeFp, String afterFp) {
        if (observed.equals(beforeFp)) {
            return SlotState.BEFORE;
        }
        if (observed.equals(afterFp)) {
            return SlotState.AFTER;
        }
        return SlotState.UNKNOWN;
    }

    /**
     * Retrieves the rolling audit history for an island up to the specified limit (capped at 50).
     */
    public List<VaultAuditLogEntry> getRecentAuditLogs(IslandId islandId, int limit) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        int boundedLimit = Math.clamp(limit, 1, 50);
        return storagePort.findRecentAuditLogs(islandId, boundedLimit);
    }
}
