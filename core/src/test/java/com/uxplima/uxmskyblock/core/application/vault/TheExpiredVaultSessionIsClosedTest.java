package com.uxplima.uxmskyblock.core.application.vault;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A page a player walked away from is let go of.
 *
 * <p>The lease is the whole point of the vault: one editor per page, so two players cannot write
 * the same chest at once. A player who crashes mid edit does not close their session, and nothing
 * closed it for them. {@code findExpiredActiveSessions} was written for exactly this and had no
 * caller anywhere in the tree, so every crash left an ACTIVE row naming a page, for as long as the
 * server ran.
 */
class TheExpiredVaultSessionIsClosedTest {

    private static final IslandId ISLAND_ID = IslandId.of(UUID.randomUUID());
    private static final ProfileId OWNER_PROFILE = ProfileId.of(UUID.randomUUID());
    private static final UUID OWNER_UUID = UUID.randomUUID();

    @Test
    @DisplayName("A session whose lease ran out is closed and its page unlocked")
    void theStaleSessionIsClosed() {
        RecordingVaultStorage storage = new RecordingVaultStorage();
        IslandVaultService service = new IslandVaultService(storage, null, 3, 5, Duration.ofSeconds(60));

        VaultSessionId stale = storage.addActiveSession(1, Instant.now().minusSeconds(120));
        VaultSessionId live = storage.addActiveSession(2, Instant.now().plusSeconds(120));

        assertThat(service.closeExpiredSessions()).isEqualTo(1);
        assertThat(storage.aborted).containsExactly(stale);
        assertThat(storage.stateOf(stale)).isEqualTo(VaultSessionState.ABORTED);
        assertThat(storage.stateOf(live)).isEqualTo(VaultSessionState.ACTIVE);
        assertThat(storage.pageOf(1).activeSessionId()).isNull();
        assertThat(storage.pageOf(2).activeSessionId()).isEqualTo(live);
    }

    @Test
    @DisplayName("One session that will not close does not stop the rest")
    void oneFailureDoesNotStopTheSweep() {
        RecordingVaultStorage storage = new RecordingVaultStorage();
        IslandVaultService service = new IslandVaultService(storage, null, 3, 5, Duration.ofSeconds(60));

        Instant ranOut = Instant.now().minusSeconds(120);
        VaultSessionId first = storage.addActiveSession(1, ranOut);
        VaultSessionId second = storage.addActiveSession(2, ranOut);
        VaultSessionId third = storage.addActiveSession(3, ranOut);
        storage.refuseToAbort(second);

        assertThat(service.closeExpiredSessions()).isEqualTo(2);
        assertThat(storage.stateOf(first)).isEqualTo(VaultSessionState.ABORTED);
        assertThat(storage.stateOf(second)).isEqualTo(VaultSessionState.ACTIVE);
        assertThat(storage.stateOf(third)).isEqualTo(VaultSessionState.ABORTED);
    }

    @Test
    @DisplayName("A sweep that finds nothing writes nothing")
    void nothingExpiredMeansNoWrite() {
        RecordingVaultStorage storage = new RecordingVaultStorage();
        IslandVaultService service = new IslandVaultService(storage, null, 3, 5, Duration.ofSeconds(60));
        storage.addActiveSession(1, Instant.now().plusSeconds(600));

        assertThat(service.closeExpiredSessions()).isZero();
        assertThat(storage.aborted).isEmpty();
    }

    @Test
    @DisplayName("The page a swept session held can be opened again")
    void theFreedPageOpensAgain() {
        RecordingVaultStorage storage = new RecordingVaultStorage();
        IslandVaultService service = new IslandVaultService(storage, null, 3, 5, Duration.ofSeconds(60));
        storage.addActiveSession(1, Instant.now().minusSeconds(120));

        service.closeExpiredSessions();

        Island island = Island.create(
                ISLAND_ID,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                PlayerUuid.of(OWNER_UUID),
                OWNER_PROFILE,
                Instant.now());
        IslandVaultService.VaultOpenResult opened =
                service.openVaultPage(island, OWNER_PROFILE, IslandRole.OWNER, 1, OWNER_UUID, Duration.ofSeconds(60));

        assertThat(opened.session().state()).isEqualTo(VaultSessionState.ACTIVE);
    }

    /** A store that remembers what it was asked to abort and can be told to refuse one. */
    private static final class RecordingVaultStorage extends InMemoryVaultStorageDouble {

        final List<VaultSessionId> aborted = new ArrayList<>();
        private final List<VaultSessionId> refused = new ArrayList<>();

        void refuseToAbort(VaultSessionId sessionId) {
            refused.add(sessionId);
        }

        @Override
        public boolean abortEditSession(VaultSessionId sessionId) {
            if (refused.contains(sessionId)) {
                throw new IllegalStateException("The row is locked by something else");
            }
            boolean closed = super.abortEditSession(sessionId);
            if (closed) {
                aborted.add(sessionId);
            }
            return closed;
        }
    }

    /** The smallest store the sweep needs: pages, sessions, and the query that finds stale ones. */
    private static class InMemoryVaultStorageDouble implements IslandVaultStoragePort {

        private final java.util.Map<Integer, VaultPage> pages = new java.util.HashMap<>();
        private final java.util.Map<VaultSessionId, VaultEditSession> sessions = new java.util.LinkedHashMap<>();

        VaultSessionId addActiveSession(int page, Instant expiresAt) {
            VaultSessionId sessionId = VaultSessionId.random();
            sessions.put(
                    sessionId,
                    new VaultEditSession(
                            sessionId,
                            ISLAND_ID,
                            page,
                            OWNER_UUID,
                            1L,
                            1L,
                            VaultSessionState.ACTIVE,
                            null,
                            expiresAt.minusSeconds(60),
                            expiresAt,
                            null));
            pages.put(
                    page,
                    new VaultPage(
                            ISLAND_ID, page, 1L, 1L, sessionId, new byte[0], OWNER_UUID.toString(), Instant.now()));
            return sessionId;
        }

        VaultSessionState stateOf(VaultSessionId sessionId) {
            return java.util.Objects.requireNonNull(sessions.get(sessionId)).state();
        }

        VaultPage pageOf(int page) {
            return java.util.Objects.requireNonNull(pages.get(page));
        }

        @Override
        public List<VaultEditSession> findExpiredActiveSessions() {
            Instant now = Instant.now();
            return sessions.values().stream()
                    .filter(session -> session.state() == VaultSessionState.ACTIVE && session.isExpired(now))
                    .toList();
        }

        @Override
        public boolean abortEditSession(VaultSessionId sessionId) {
            VaultEditSession session = sessions.get(sessionId);
            if (session == null || session.state() != VaultSessionState.ACTIVE) {
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
            VaultPage held = pages.get(session.page());
            if (held != null && sessionId.equals(held.activeSessionId())) {
                pages.put(
                        session.page(),
                        new VaultPage(
                                held.islandId(),
                                held.page(),
                                held.pageVersion(),
                                held.leaseEpoch(),
                                null,
                                held.contentsNbt(),
                                held.lastModifiedBy(),
                                Instant.now()));
            }
            return true;
        }

        @Override
        public Optional<VaultPage> findPage(IslandId islandId, int page) {
            return Optional.ofNullable(pages.get(page));
        }

        @Override
        public List<VaultPage> findAllPages(IslandId islandId) {
            return List.copyOf(pages.values());
        }

        @Override
        public VaultPage createPage(IslandId islandId, int page, byte[] initialContentsNbt, String createdBy) {
            VaultPage created =
                    new VaultPage(islandId, page, 1L, 1L, null, initialContentsNbt, createdBy, Instant.now());
            pages.put(page, created);
            return created;
        }

        @Override
        public Optional<VaultEditSession> acquireEditSession(
                IslandId islandId, int page, UUID playerUuid, Duration leaseDuration) {
            VaultPage held = pages.get(page);
            long version = held == null ? 1L : held.pageVersion();
            long epoch = held == null ? 1L : held.leaseEpoch() + 1L;
            VaultSessionId sessionId = VaultSessionId.random();
            Instant now = Instant.now();
            VaultEditSession session = new VaultEditSession(
                    sessionId,
                    islandId,
                    page,
                    playerUuid,
                    epoch,
                    version,
                    VaultSessionState.ACTIVE,
                    null,
                    now,
                    now.plus(leaseDuration),
                    null);
            sessions.put(sessionId, session);
            pages.put(
                    page,
                    new VaultPage(
                            islandId,
                            page,
                            version,
                            epoch,
                            sessionId,
                            held == null ? new byte[0] : held.contentsNbt(),
                            playerUuid.toString(),
                            now));
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
                byte @org.jspecify.annotations.Nullable [] playerInventoryNbt,
                @org.jspecify.annotations.Nullable ProfileId playerProfileId) {
            return false;
        }

        @Override
        public void recordEscrowTransfer(com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord transfer) {}

        @Override
        public void updateEscrowTransferState(
                UUID transferId, com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState state) {}

        @Override
        public List<com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord> findEscrowTransfers(
                VaultSessionId sessionId) {
            return List.of();
        }

        @Override
        public void appendAuditLog(com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry entry) {}

        @Override
        public void appendAuditLogs(List<com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry> entries) {}

        @Override
        public int trimAuditLogs(int keepPerPage) {
            return 0;
        }

        @Override
        public List<com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry> findRecentAuditLogs(
                IslandId islandId, int limit) {
            return List.of();
        }
    }
}
