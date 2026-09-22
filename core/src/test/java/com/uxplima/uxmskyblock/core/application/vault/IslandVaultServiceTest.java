package com.uxplima.uxmskyblock.core.application.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.vault.DualSlotRecoveryAction;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.StaleVaultSessionException;
import com.uxplima.uxmskyblock.core.domain.vault.TransferSourceType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageBusyException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPermissionDeniedException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandVaultServiceTest {

    private InMemoryVaultStorage storage;
    private IslandVaultService vaultService;

    private static final IslandId ISLAND_ID = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER_PROFILE = ProfileId.of(UUID.randomUUID());
    private static final UUID OWNER_UUID = UUID.randomUUID();

    private Island testIsland;

    @BeforeEach
    void setUp() {
        storage = new InMemoryVaultStorage();
        vaultService = new IslandVaultService(storage, null, 1, 5, Duration.ofSeconds(60));
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        testIsland = Island.create(ISLAND_ID, bounds, PlayerUuid.of(OWNER_UUID), OWNER_PROFILE, Instant.now());
    }

    @Test
    @DisplayName("openVaultPage requires VAULT_VIEW permission")
    void openVaultPageRequiresPermission() {
        IslandRole visitor = IslandRole.VISITOR; // Has no permissions
        assertThatThrownBy(() -> vaultService.openVaultPage(
                        testIsland,
                        ProfileId.of(UUID.randomUUID()),
                        visitor,
                        1,
                        UUID.randomUUID(),
                        Duration.ofSeconds(30)))
                .isInstanceOf(VaultPermissionDeniedException.class);
    }

    @Test
    @DisplayName("openVaultPage rejects page exceeding max allowed pages")
    void openVaultPageRejectsPageLimitExceeded() {
        assertThatThrownBy(() -> vaultService.openVaultPage(
                        testIsland, OWNER_PROFILE, IslandRole.OWNER, 2, OWNER_UUID, Duration.ofSeconds(30)))
                .isInstanceOf(VaultPageLimitExceededException.class);
    }

    @Test
    @DisplayName("openVaultPage creates initial page and acquires exclusive lease")
    void openVaultPageCreatesPageAndAcquiresLease() {
        IslandVaultService.VaultOpenResult result = vaultService.openVaultPage(
                testIsland, OWNER_PROFILE, IslandRole.OWNER, 1, OWNER_UUID, Duration.ofSeconds(30));

        assertThat(result).isNotNull();
        assertThat(result.page().page()).isEqualTo(1);
        assertThat(result.session().state()).isEqualTo(VaultSessionState.ACTIVE);
        assertThat(result.session().playerUuid()).isEqualTo(OWNER_UUID);
    }

    @Test
    @DisplayName("openVaultPage throws VaultPageBusyException when page is currently locked")
    void openVaultPageThrowsBusyExceptionWhenLocked() {
        vaultService.openVaultPage(testIsland, OWNER_PROFILE, IslandRole.OWNER, 1, OWNER_UUID, Duration.ofSeconds(60));

        UUID secondPlayerUuid = UUID.randomUUID();
        ProfileId secondProfileId = ProfileId.of(UUID.randomUUID());

        assertThatThrownBy(() -> vaultService.openVaultPage(
                        testIsland, secondProfileId, IslandRole.MODERATOR, 1, secondPlayerUuid, Duration.ofSeconds(60)))
                .isInstanceOf(VaultPageBusyException.class);
    }

    @Test
    @DisplayName("stageTransferIntent stages write-ahead transfer with INTENT state")
    void stageTransferIntentSucceeds() {
        VaultSessionId sessionId = VaultSessionId.random();
        EscrowTransferRecord transfer = vaultService.stageTransferIntent(
                sessionId,
                TransferSourceType.VAULT,
                TransferSourceType.PLAYER,
                5,
                12,
                "src-before",
                "src-after",
                "dst-before",
                "dst-after",
                1,
                1,
                1,
                1,
                new byte[] {1, 2, 3},
                64);

        assertThat(transfer.state()).isEqualTo(EscrowTransferState.INTENT);
        assertThat(transfer.sourceSlot()).isEqualTo(5);
        assertThat(transfer.destinationSlot()).isEqualTo(12);

        vaultService.markTransferApplied(transfer.transferId());
        EscrowTransferRecord updated = Objects.requireNonNull(storage.transfers.get(transfer.transferId()));
        assertThat(updated.state()).isEqualTo(EscrowTransferState.APPLIED);
    }

    @Test
    @DisplayName("commitVaultPage commits atomically and persists audit logs")
    void commitVaultPageSucceeds() {
        IslandVaultService.VaultOpenResult openResult = vaultService.openVaultPage(
                testIsland, OWNER_PROFILE, IslandRole.OWNER, 1, OWNER_UUID, Duration.ofSeconds(60));

        VaultAuditLogEntry entry = VaultAuditLogEntry.create(
                ISLAND_ID, 1, OWNER_PROFILE.toString(), VaultActionType.DEPOSIT, 0, "DIAMOND x64", 64);

        vaultService.commitVaultPage(
                openResult.session().sessionId(),
                new byte[] {9, 9, 9},
                OWNER_PROFILE.toString(),
                null,
                null,
                List.of(entry));

        VaultPage updatedPage = Objects.requireNonNull(storage.pages.get(1));
        assertThat(updatedPage.contentsNbt()).isEqualTo(new byte[] {9, 9, 9});
        assertThat(updatedPage.pageVersion()).isEqualTo(2);

        List<VaultAuditLogEntry> auditLogs = vaultService.getRecentAuditLogs(ISLAND_ID, 10);
        assertThat(auditLogs).hasSize(1);
        assertThat(auditLogs.get(0).itemSummary()).isEqualTo("DIAMOND x64");
    }

    @Test
    @DisplayName("The read is bounded by what the operator keeps, not by a number written in the code")
    void theOperatorBoundsTheAuditRead() {
        IslandVaultService keepingThree = new IslandVaultService(storage, null, 1, 5, Duration.ofSeconds(60), 3);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            storage.auditLogs.add(new VaultAuditLogEntry(
                    UUID.randomUUID(),
                    ISLAND_ID,
                    1,
                    OWNER_PROFILE.toString(),
                    VaultActionType.DEPOSIT,
                    0,
                    "STONE_" + i,
                    1,
                    base.plusSeconds(i)));
        }

        assertThat(keepingThree.auditRetentionPerPage()).isEqualTo(3);
        assertThat(keepingThree.getRecentAuditLogs(ISLAND_ID, 50))
                .describedAs("asking for fifty when a page keeps three")
                .hasSize(3);
    }

    @Test
    @DisplayName("The trim hands the operator's retention to the store")
    void theTrimUsesTheOperatorsRetention() {
        IslandVaultService keepingThree = new IslandVaultService(storage, null, 1, 5, Duration.ofSeconds(60), 3);
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            storage.auditLogs.add(new VaultAuditLogEntry(
                    UUID.randomUUID(),
                    ISLAND_ID,
                    1,
                    OWNER_PROFILE.toString(),
                    VaultActionType.DEPOSIT,
                    0,
                    "STONE_" + i,
                    1,
                    base.plusSeconds(i)));
        }

        assertThat(keepingThree.trimAuditLogs()).describedAs("seven dropped").isEqualTo(7);
        assertThat(storage.auditLogs)
                .extracting(VaultAuditLogEntry::itemSummary)
                .containsExactlyInAnyOrder("STONE_9", "STONE_8", "STONE_7");
    }

    @Test
    @DisplayName("A retention of nothing is refused rather than accepted")
    void aRetentionOfNothingIsRefused() {
        assertThatThrownBy(() -> new IslandVaultService(storage, null, 1, 5, Duration.ofSeconds(60), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("commitVaultPage throws StaleVaultSessionException on concurrent conflict")
    void commitVaultPageThrowsStaleSessionException() {
        storage.failNextCommit = true;
        VaultSessionId sessionId = VaultSessionId.random();

        assertThatThrownBy(() -> vaultService.commitVaultPage(
                        sessionId, new byte[] {1}, OWNER_PROFILE.toString(), null, null, List.of()))
                .isInstanceOf(StaleVaultSessionException.class);
    }

    @Test
    @DisplayName("DualSlotRecovery decision matrix classifies states correctly")
    void dualSlotRecoveryMatrixEvaluation() {
        VaultSessionId sessionId = VaultSessionId.random();
        EscrowTransferRecord transfer = new EscrowTransferRecord(
                UUID.randomUUID(),
                sessionId,
                TransferSourceType.VAULT,
                TransferSourceType.PLAYER,
                0,
                0,
                "src-before",
                "src-after",
                "dst-before",
                "dst-after",
                1,
                1,
                1,
                1,
                new byte[] {1},
                1,
                EscrowTransferState.INTENT,
                Instant.now(),
                Instant.now());

        // BEFORE + BEFORE -> ABORT
        assertThat(vaultService.evaluateDualSlotRecovery(transfer, "src-before", "dst-before"))
                .isEqualTo(DualSlotRecoveryAction.ABORT);

        // AFTER + AFTER -> CONDITIONAL_ROLLBACK
        assertThat(vaultService.evaluateDualSlotRecovery(transfer, "src-after", "dst-after"))
                .isEqualTo(DualSlotRecoveryAction.CONDITIONAL_ROLLBACK);

        // BEFORE + AFTER -> RECOVERY_REQUIRED (Duplication hazard)
        assertThat(vaultService.evaluateDualSlotRecovery(transfer, "src-before", "dst-after"))
                .isEqualTo(DualSlotRecoveryAction.RECOVERY_REQUIRED);

        // AFTER + BEFORE -> CONDITIONAL_ROLLBACK (Item loss hazard)
        assertThat(vaultService.evaluateDualSlotRecovery(transfer, "src-after", "dst-before"))
                .isEqualTo(DualSlotRecoveryAction.CONDITIONAL_ROLLBACK);

        // UNKNOWN drift -> RECOVERY_REQUIRED
        assertThat(vaultService.evaluateDualSlotRecovery(transfer, "tampered", "dst-before"))
                .isEqualTo(DualSlotRecoveryAction.RECOVERY_REQUIRED);
        assertThat(vaultService.evaluateDualSlotRecovery(transfer, "src-before", "tampered"))
                .isEqualTo(DualSlotRecoveryAction.RECOVERY_REQUIRED);
    }

    private static class InMemoryVaultStorage implements IslandVaultStoragePort {
        final Map<Integer, VaultPage> pages = new HashMap<>();
        final Map<VaultSessionId, VaultEditSession> sessions = new HashMap<>();
        final Map<UUID, EscrowTransferRecord> transfers = new HashMap<>();
        final List<VaultAuditLogEntry> auditLogs = new ArrayList<>();
        boolean failNextCommit = false;

        @Override
        public Optional<VaultPage> findPage(IslandId islandId, int page) {
            return Optional.ofNullable(pages.get(page));
        }

        @Override
        public List<VaultPage> findAllPages(IslandId islandId) {
            return new ArrayList<>(pages.values());
        }

        @Override
        public VaultPage createPage(IslandId islandId, int page, byte[] initialContentsNbt, String createdBy) {
            VaultPage newPage = VaultPage.initial(islandId, page, initialContentsNbt, createdBy);
            pages.put(page, newPage);
            return newPage;
        }

        @Override
        public Optional<VaultEditSession> acquireEditSession(
                IslandId islandId, int page, UUID playerUuid, Duration leaseDuration) {
            VaultPage p = pages.get(page);
            if (p == null) {
                return Optional.empty();
            }
            if (p.activeSessionId() != null) {
                VaultEditSession active = sessions.get(p.activeSessionId());
                if (active != null && !active.isExpired(Instant.now())) {
                    return Optional.empty();
                }
            }

            VaultSessionId newSessionId = VaultSessionId.random();
            long newEpoch = p.leaseEpoch() + 1;
            Instant now = Instant.now();
            VaultEditSession session = new VaultEditSession(
                    newSessionId,
                    islandId,
                    page,
                    playerUuid,
                    newEpoch,
                    p.pageVersion(),
                    VaultSessionState.ACTIVE,
                    null,
                    now,
                    now.plus(leaseDuration),
                    null);
            sessions.put(newSessionId, session);

            VaultPage lockedPage = new VaultPage(
                    islandId,
                    page,
                    p.pageVersion(),
                    newEpoch,
                    newSessionId,
                    p.contentsNbt(),
                    playerUuid.toString(),
                    now);
            pages.put(page, lockedPage);
            return Optional.of(session);
        }

        @Override
        public Optional<VaultEditSession> findSession(VaultSessionId sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public boolean commitEditSession(
                VaultSessionId sessionId,
                byte[] newContentsNbt,
                String modifiedBy,
                byte @Nullable [] playerInventoryNbt,
                @Nullable ProfileId playerProfileId) {
            if (failNextCommit) {
                return false;
            }
            VaultEditSession session = sessions.get(sessionId);
            if (session == null || session.state() != VaultSessionState.ACTIVE) {
                return false;
            }
            VaultPage p = pages.get(session.page());
            if (p == null || !sessionId.equals(p.activeSessionId())) {
                return false;
            }

            VaultPage committedPage = new VaultPage(
                    p.islandId(),
                    p.page(),
                    p.pageVersion() + 1,
                    p.leaseEpoch(),
                    null,
                    newContentsNbt,
                    modifiedBy,
                    Instant.now());
            pages.put(p.page(), committedPage);

            VaultEditSession committedSession = new VaultEditSession(
                    session.sessionId(),
                    session.islandId(),
                    session.page(),
                    session.playerUuid(),
                    session.leaseEpoch(),
                    session.basePageVersion(),
                    VaultSessionState.COMMITTED,
                    session.escrowJournal(),
                    session.openedAt(),
                    session.expiresAt(),
                    Instant.now());
            sessions.put(sessionId, committedSession);
            return true;
        }

        @Override
        public boolean abortEditSession(VaultSessionId sessionId) {
            VaultEditSession session = sessions.get(sessionId);
            if (session == null || session.state() != VaultSessionState.ACTIVE) {
                // The real store aborts under WHERE state = 'ACTIVE' and reports what it changed.
                return false;
            }
            sessions.put(
                    sessionId,
                    new VaultEditSession(
                            session.sessionId(),
                            session.islandId(),
                            session.page(),
                            session.playerUuid(),
                            session.leaseEpoch(),
                            session.basePageVersion(),
                            VaultSessionState.ABORTED,
                            session.escrowJournal(),
                            session.openedAt(),
                            session.expiresAt(),
                            Instant.now()));
            VaultPage p = pages.get(session.page());
            if (p != null && sessionId.equals(p.activeSessionId())) {
                VaultPage unlocked = new VaultPage(
                        p.islandId(),
                        p.page(),
                        p.pageVersion(),
                        p.leaseEpoch(),
                        null,
                        p.contentsNbt(),
                        p.lastModifiedBy(),
                        Instant.now());
                pages.put(p.page(), unlocked);
            }
            return true;
        }

        @Override
        public void recordEscrowTransfer(EscrowTransferRecord transfer) {
            transfers.put(transfer.transferId(), transfer);
        }

        @Override
        public void updateEscrowTransferState(UUID transferId, EscrowTransferState state) {
            EscrowTransferRecord existing = transfers.get(transferId);
            if (existing != null) {
                transfers.put(
                        transferId,
                        new EscrowTransferRecord(
                                existing.transferId(),
                                existing.sessionId(),
                                existing.source(),
                                existing.destination(),
                                existing.sourceSlot(),
                                existing.destinationSlot(),
                                existing.sourceBeforeFingerprint(),
                                existing.sourceAfterFingerprint(),
                                existing.destinationBeforeFingerprint(),
                                existing.destinationAfterFingerprint(),
                                existing.sourceExpectedVersion(),
                                existing.destinationExpectedVersion(),
                                existing.sourceContainerVersion(),
                                existing.destinationContainerVersion(),
                                existing.serializedItemNbt(),
                                existing.quantity(),
                                state,
                                existing.createdAt(),
                                Instant.now()));
            }
        }

        @Override
        public List<EscrowTransferRecord> findEscrowTransfers(VaultSessionId sessionId) {
            return transfers.values().stream()
                    .filter(t -> t.sessionId().equals(sessionId))
                    .toList();
        }

        @Override
        public void appendAuditLog(VaultAuditLogEntry logEntry) {
            auditLogs.add(logEntry);
        }

        @Override
        public void appendAuditLogs(List<VaultAuditLogEntry> entries) {
            auditLogs.addAll(entries);
        }

        @Override
        public int trimAuditLogs(int keepPerPage) {
            Map<Integer, Integer> keptPerPage = new HashMap<>();
            List<VaultAuditLogEntry> newestFirst = new ArrayList<>(auditLogs);
            newestFirst.sort(java.util.Comparator.comparing(VaultAuditLogEntry::createdAt)
                    .thenComparing(entry -> entry.logId().toString())
                    .reversed());
            List<VaultAuditLogEntry> kept = new ArrayList<>();
            int dropped = 0;
            for (VaultAuditLogEntry entry : newestFirst) {
                int seen = keptPerPage.merge(entry.page(), 1, Integer::sum);
                if (seen <= keepPerPage) {
                    kept.add(entry);
                } else {
                    dropped++;
                }
            }
            auditLogs.clear();
            auditLogs.addAll(kept);
            return dropped;
        }

        @Override
        public List<VaultAuditLogEntry> findRecentAuditLogs(IslandId islandId, int limit) {
            return auditLogs.stream()
                    .filter(l -> l.islandId().equals(islandId))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<VaultEditSession> findExpiredActiveSessions() {
            Instant now = Instant.now();
            return sessions.values().stream()
                    .filter(s -> s.state() == VaultSessionState.ACTIVE && s.isExpired(now))
                    .toList();
        }
    }
}
