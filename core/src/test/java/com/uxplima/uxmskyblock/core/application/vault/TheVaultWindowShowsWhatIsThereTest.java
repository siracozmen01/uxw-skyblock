package com.uxplima.uxmskyblock.core.application.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPageBusyException;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A vault window shows what the page holds now, not what it held before the last commit.
 *
 * <p>The contents used to be read before the lease was taken. That opens a window: the player
 * holding the page commits between the read and the lease, and the next window shows what the page
 * held before that commit. Closing it writes those contents back plus whatever the new player
 * added, so the previous holder's deposit is erased or their withdrawal is undone. Either way items
 * appear or disappear, and the page is the only record, so nothing says it happened.
 */
class TheVaultWindowShowsWhatIsThereTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER = ProfileId.of(UUID.randomUUID());
    private static final byte[] TEN_DIAMONDS = "ten diamonds".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NOTHING_LEFT = "emptied".getBytes(StandardCharsets.UTF_8);

    private RacingVaultStorage storage;
    private IslandVaultService vault;
    private Island island;

    /**
     * A storage whose page is committed by somebody else in the moment between a read and a lease.
     *
     * <p>That is the whole race, forced rather than hoped for: a test that waits for two threads to
     * interleave the right way is a test that passes on a quiet machine.
     */
    private static class RacingVaultStorage implements IslandVaultStoragePort {
        private VaultPage page;
        private final List<VaultEditSession> sessions = new ArrayList<>();
        boolean commitOnNextLease;

        RacingVaultStorage(VaultPage page) {
            this.page = page;
        }

        @Override
        public Optional<VaultPage> findPage(IslandId islandId, int pageNumber) {
            return Optional.of(page);
        }

        @Override
        public List<VaultPage> findAllPages(IslandId islandId) {
            return List.of(page);
        }

        @Override
        public VaultPage createPage(IslandId islandId, int pageNumber, byte[] initialContentsNbt, String createdBy) {
            return page;
        }

        @Override
        public Optional<VaultEditSession> acquireEditSession(
                IslandId islandId, int pageNumber, UUID playerUuid, Duration leaseDuration) {
            if (commitOnNextLease) {
                // The previous holder's commit lands here, between a read and this lease.
                commitOnNextLease = false;
                page = new VaultPage(
                        page.islandId(),
                        page.page(),
                        page.pageVersion() + 1,
                        page.leaseEpoch() + 1,
                        null,
                        NOTHING_LEFT,
                        "the previous holder",
                        Instant.now());
            }
            VaultEditSession session = new VaultEditSession(
                    VaultSessionId.random(),
                    islandId,
                    pageNumber,
                    playerUuid,
                    page.leaseEpoch() + 1,
                    page.pageVersion(),
                    VaultSessionState.ACTIVE,
                    null,
                    Instant.now(),
                    Instant.now().plus(leaseDuration),
                    null);
            sessions.add(session);
            return Optional.of(session);
        }

        @Override
        public Optional<VaultEditSession> findSession(VaultSessionId sessionId) {
            return sessions.stream()
                    .filter(s -> s.sessionId().equals(sessionId))
                    .findFirst();
        }

        @Override
        public boolean commitEditSession(
                VaultSessionId sessionId,
                byte[] newContentsNbt,
                String modifiedBy,
                com.uxplima.uxmskyblock.core.domain.inventory.@Nullable PlayerStateWrite playerState) {
            return true;
        }

        @Override
        public boolean abortEditSession(VaultSessionId sessionId) {
            return true;
        }

        @Override
        public void recordEscrowTransfer(EscrowTransferRecord transfer) {}

        @Override
        public void updateEscrowTransferState(UUID transferId, EscrowTransferState state) {}

        @Override
        public List<EscrowTransferRecord> findEscrowTransfers(VaultSessionId sessionId) {
            return List.of();
        }

        @Override
        public void appendAuditLog(VaultAuditLogEntry logEntry) {}

        @Override
        public void appendAuditLogs(List<VaultAuditLogEntry> entries) {}

        @Override
        public int trimAuditLogs(int keepPerPage) {
            return 0;
        }

        @Override
        public List<VaultAuditLogEntry> findRecentAuditLogs(IslandId islandId, int limit) {
            return List.of();
        }

        @Override
        public List<VaultEditSession> findExpiredActiveSessions() {
            return List.of();
        }
    }

    @BeforeEach
    void setUp() {
        island = Island.create(
                ISLAND,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                PlayerUuid.of(UUID.randomUUID()),
                OWNER,
                Instant.now());
        storage =
                new RacingVaultStorage(new VaultPage(ISLAND, 1, 1L, 1L, null, TEN_DIAMONDS, "somebody", Instant.now()));
        vault = new IslandVaultService(storage, null);
    }

    @Test
    @DisplayName("A window opened while somebody else commits shows what they left, not what was there before")
    void thewindowShowsTheCommittedContents() {
        storage.commitOnNextLease = true;

        IslandVaultService.VaultOpenResult opened =
                vault.openVaultPage(island, OWNER, IslandRole.OWNER, 1, UUID.randomUUID(), Duration.ofSeconds(60));

        assertThat(opened.page().contentsNbt())
                .describedAs("showing the older contents is how a commit gets written back over a newer one")
                .isEqualTo(NOTHING_LEFT);
    }

    @Test
    @DisplayName("A window opened with nobody else about shows the page as it stands")
    void thewindowShowsThePageAsItStands() {
        IslandVaultService.VaultOpenResult opened =
                vault.openVaultPage(island, OWNER, IslandRole.OWNER, 1, UUID.randomUUID(), Duration.ofSeconds(60));

        assertThat(opened.page().contentsNbt()).isEqualTo(TEN_DIAMONDS);
    }

    @Test
    @DisplayName("The session handed back is the one that was just taken")
    void thesessionIsTheOneJustTaken() {
        UUID player = UUID.randomUUID();

        IslandVaultService.VaultOpenResult opened =
                vault.openVaultPage(island, OWNER, IslandRole.OWNER, 1, player, Duration.ofSeconds(60));

        assertThat(opened.session().playerUuid()).isEqualTo(player);
        assertThat(opened.session().state()).isEqualTo(VaultSessionState.ACTIVE);
    }

    @Test
    @DisplayName("A page somebody else is holding is refused, and the refusal names them")
    void abusyPageNamesItsHolder() {
        UUID holder = UUID.randomUUID();
        IslandVaultService.VaultOpenResult held =
                vault.openVaultPage(island, OWNER, IslandRole.OWNER, 1, holder, Duration.ofSeconds(60));
        RacingVaultStorage refusing =
                new RacingVaultStorage(new VaultPage(
                        ISLAND, 1, 1L, 1L, held.session().sessionId(), TEN_DIAMONDS, "somebody", Instant.now())) {
                    @Override
                    public Optional<VaultEditSession> acquireEditSession(
                            IslandId islandId, int pageNumber, UUID playerUuid, Duration leaseDuration) {
                        return Optional.empty();
                    }
                };
        refusing.findSession(held.session().sessionId());
        IslandVaultService busyVault = new IslandVaultService(refusing, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> busyVault.openVaultPage(
                        island, OWNER, IslandRole.OWNER, 1, UUID.randomUUID(), Duration.ofSeconds(60)))
                .isInstanceOf(VaultPageBusyException.class);
    }
}
