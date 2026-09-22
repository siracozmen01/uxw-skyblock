package com.uxplima.uxmskyblock.persistence.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.TransferSourceType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultActionType;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SqlIslandVaultStorageAdapterSqliteTest {

    private Database database;
    private SqlIslandVaultStorageAdapter adapter;

    private static final IslandId ISLAND_ID = IslandId.fromString("99999999-9999-9999-9999-999999999999");
    private static final ProfileId OWNER_PROFILE = ProfileId.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));

        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("INSERT INTO player_accounts (player_uuid) VALUES ('00000000-0000-0000-0000-000000000001');");
            stmt.execute(
                    "INSERT INTO player_profiles (profile_id, player_uuid, profile_type) VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001', 'CLASSIC');");
            stmt.execute("""
                    INSERT INTO islands (
                        id, owner_profile_id, owner_account_uuid, custom_name, lifecycle,
                        economic_state, administrative_state, level_score, net_worth_minor_units, version
                    ) VALUES (
                        '99999999-9999-9999-9999-999999999999',
                        '11111111-1111-1111-1111-111111111111',
                        '00000000-0000-0000-0000-000000000001',
                        'Alpha Island',
                        'ACTIVE',
                        'NORMAL',
                        'NORMAL',
                        100,
                        50000,
                        1
                    );
                    """);
        }

        adapter = new SqlIslandVaultStorageAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("createPage and findPage persist and retrieve vault pages")
    void createAndFindPage() {
        byte[] initialData = new byte[] {1, 2, 3};
        VaultPage page = adapter.createPage(ISLAND_ID, 1, initialData, OWNER_PROFILE.toString());

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.contentsNbt()).isEqualTo(initialData);
        assertThat(page.pageVersion()).isEqualTo(1);
        assertThat(page.leaseEpoch()).isEqualTo(1);

        Optional<VaultPage> found = adapter.findPage(ISLAND_ID, 1);
        assertThat(found).isPresent();
        assertThat(found.get().contentsNbt()).isEqualTo(initialData);

        List<VaultPage> allPages = adapter.findAllPages(ISLAND_ID);
        assertThat(allPages).hasSize(1);
    }

    @Test
    @DisplayName("acquireEditSession acquires lease, increments epoch, and rejects concurrent editor")
    void acquireEditSessionAndRejectConcurrent() {
        adapter.createPage(ISLAND_ID, 1, new byte[0], OWNER_PROFILE.toString());

        Optional<VaultEditSession> session1 =
                adapter.acquireEditSession(ISLAND_ID, 1, OWNER_UUID, Duration.ofSeconds(60));
        assertThat(session1).isPresent();
        assertThat(session1.get().state()).isEqualTo(VaultSessionState.ACTIVE);
        assertThat(session1.get().leaseEpoch()).isEqualTo(2);

        // Second player tries while active -> busy (empty)
        UUID secondPlayer = UUID.randomUUID();
        Optional<VaultEditSession> session2 =
                adapter.acquireEditSession(ISLAND_ID, 1, secondPlayer, Duration.ofSeconds(60));
        assertThat(session2).isEmpty();

        // Abort active session -> new lease succeeds
        adapter.abortEditSession(session1.get().sessionId());
        Optional<VaultEditSession> session3 =
                adapter.acquireEditSession(ISLAND_ID, 1, secondPlayer, Duration.ofSeconds(60));
        assertThat(session3).isPresent();
        assertThat(session3.get().playerUuid()).isEqualTo(secondPlayer);

        // Expired lease takeover test
        adapter.createPage(ISLAND_ID, 2, new byte[0], OWNER_PROFILE.toString());
        Optional<VaultEditSession> shortSession =
                adapter.acquireEditSession(ISLAND_ID, 2, OWNER_UUID, Duration.ofMillis(5));
        assertThat(shortSession).isPresent();
        try {
            Thread.sleep(25);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Optional<VaultEditSession> takeoverSession =
                adapter.acquireEditSession(ISLAND_ID, 2, secondPlayer, Duration.ofSeconds(60));
        assertThat(takeoverSession).isPresent();
        assertThat(takeoverSession.get().playerUuid()).isEqualTo(secondPlayer);
    }

    @Test
    @DisplayName("commitEditSession commits page with CAS and updates transfers")
    void commitEditSessionSucceeds() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        Optional<VaultEditSession> session =
                adapter.acquireEditSession(ISLAND_ID, 1, OWNER_UUID, Duration.ofSeconds(60));
        assertThat(session).isPresent();
        VaultSessionId sessionId = session.get().sessionId();

        // Stage an escrow transfer
        UUID transferId = UUID.randomUUID();
        EscrowTransferRecord transfer = new EscrowTransferRecord(
                transferId,
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
                new byte[] {5},
                1,
                EscrowTransferState.INTENT,
                java.time.Instant.now(),
                java.time.Instant.now());
        adapter.recordEscrowTransfer(transfer);

        byte[] newContents = new byte[] {2, 3, 4};
        boolean committed = adapter.commitEditSession(sessionId, newContents, OWNER_PROFILE.toString(), null, null);
        assertThat(committed).isTrue();

        Optional<VaultPage> updatedPage = adapter.findPage(ISLAND_ID, 1);
        assertThat(updatedPage).isPresent();
        assertThat(updatedPage.get().contentsNbt()).isEqualTo(newContents);
        assertThat(updatedPage.get().pageVersion()).isEqualTo(2);
        assertThat(updatedPage.get().activeSessionId()).isNull();

        Optional<VaultEditSession> committedSession = adapter.findSession(sessionId);
        assertThat(committedSession).isPresent();
        assertThat(committedSession.get().state()).isEqualTo(VaultSessionState.COMMITTED);

        List<EscrowTransferRecord> transfers = adapter.findEscrowTransfers(sessionId);
        assertThat(transfers).hasSize(1);
        assertThat(transfers.get(0).state()).isEqualTo(EscrowTransferState.COMMITTED);

        // Subsequent commit with same session fails
        boolean repeatCommit = adapter.commitEditSession(sessionId, newContents, OWNER_PROFILE.toString(), null, null);
        assertThat(repeatCommit).isFalse();
    }

    @Test
    @DisplayName("abortEditSession clears lock and sets state to ABORTED")
    void abortEditSessionSucceeds() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        Optional<VaultEditSession> session =
                adapter.acquireEditSession(ISLAND_ID, 1, OWNER_UUID, Duration.ofSeconds(60));
        assertThat(session).isPresent();
        VaultSessionId sessionId = session.get().sessionId();

        boolean aborted = adapter.abortEditSession(sessionId);
        assertThat(aborted).isTrue();

        Optional<VaultPage> page = adapter.findPage(ISLAND_ID, 1);
        assertThat(page).isPresent();
        assertThat(page.get().activeSessionId()).isNull();

        Optional<VaultEditSession> abortedSession = adapter.findSession(sessionId);
        assertThat(abortedSession).isPresent();
        assertThat(abortedSession.get().state()).isEqualTo(VaultSessionState.ABORTED);
    }

    @Test
    @DisplayName("Trimming keeps the newest entries of every page and drops the rest")
    void trimmingKeepsTheNewestPerPage() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        adapter.createPage(ISLAND_ID, 2, new byte[] {2}, OWNER_PROFILE.toString());
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            adapter.appendAuditLog(auditEntry(1, base.plusSeconds(i), "PAGE1_" + i, i + 1));
        }
        for (int i = 0; i < 3; i++) {
            adapter.appendAuditLog(auditEntry(2, base.plusSeconds(i), "PAGE2_" + i, i + 1));
        }

        assertThat(adapter.trimAuditLogs(2))
                .describedAs("three dropped off page one and one off page two")
                .isEqualTo(4);

        List<VaultAuditLogEntry> left = adapter.findRecentAuditLogs(ISLAND_ID, 50);
        assertThat(left)
                .describedAs("two per page, the newest")
                .extracting(VaultAuditLogEntry::itemSummary)
                .containsExactlyInAnyOrder("PAGE1_4", "PAGE1_3", "PAGE2_2", "PAGE2_1");
    }

    @Test
    @DisplayName("Trimming a page that has not outgrown its retention drops nothing")
    void trimmingASmallPageDropsNothing() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        Instant base = Instant.parse("2026-09-01T00:00:00Z");
        adapter.appendAuditLog(auditEntry(1, base, "ONLY", 1));

        assertThat(adapter.trimAuditLogs(50)).isZero();
        assertThat(adapter.findRecentAuditLogs(ISLAND_ID, 50)).hasSize(1);
    }

    @Test
    @DisplayName("A whole commit's entries go down in one batch")
    void aWholeCommitIsWrittenAtOnce() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        Instant base = Instant.parse("2026-09-01T00:00:00Z");

        adapter.appendAuditLogs(List.of(
                auditEntry(1, base, "FIRST", 1),
                auditEntry(1, base.plusSeconds(1), "SECOND", 2),
                auditEntry(1, base.plusSeconds(2), "THIRD", 3)));

        assertThat(adapter.findRecentAuditLogs(ISLAND_ID, 50))
                .extracting(VaultAuditLogEntry::itemSummary)
                .containsExactly("THIRD", "SECOND", "FIRST");
    }

    private static VaultAuditLogEntry auditEntry(int page, Instant createdAt, String summary, int quantity) {
        return new VaultAuditLogEntry(
                UUID.randomUUID(),
                ISLAND_ID,
                page,
                OWNER_PROFILE.toString(),
                VaultActionType.DEPOSIT,
                0,
                summary,
                quantity,
                createdAt);
    }

    @Test
    @DisplayName("A lease that is still running is never reported as expired")
    void aLiveLeaseIsNotExpired() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        assertThat(adapter.acquireEditSession(ISLAND_ID, 1, OWNER_UUID, Duration.ofMinutes(10)))
                .isPresent();

        // The query used to compare a timestamp written by the JVM against the database's own
        // CURRENT_TIMESTAMP. Two clocks, and under SQLite two types, so a lease a minute old read
        // as expired and the sweep would have taken the page off a player who was editing it.
        assertThat(adapter.findExpiredActiveSessions())
                .describedAs("a ten minute lease, one moment old")
                .isEmpty();
    }

    @Test
    @DisplayName("The sweep closes a lease whose holder never came back and unlocks its page")
    void theSweepClosesTheStaleLeaseOverTheRealStore() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        adapter.createPage(ISLAND_ID, 2, new byte[] {2}, OWNER_PROFILE.toString());
        Optional<VaultEditSession> abandoned =
                adapter.acquireEditSession(ISLAND_ID, 1, OWNER_UUID, Duration.ofMillis(5));
        Optional<VaultEditSession> open = adapter.acquireEditSession(ISLAND_ID, 2, OWNER_UUID, Duration.ofSeconds(60));
        assertThat(abandoned).isPresent();
        assertThat(open).isPresent();
        waitPast(Duration.ofMillis(5));

        IslandVaultService service = new IslandVaultService(adapter, null, 1, 10, Duration.ofSeconds(60));
        assertThat(service.closeExpiredSessions())
                .describedAs("sessions closed")
                .isEqualTo(1);

        Optional<VaultEditSession> closed = adapter.findSession(abandoned.get().sessionId());
        assertThat(closed).isPresent();
        assertThat(closed.get().state()).isEqualTo(VaultSessionState.ABORTED);
        assertThat(adapter.findPage(ISLAND_ID, 1).orElseThrow().activeSessionId())
                .describedAs("the page the crashed player held is free again")
                .isNull();

        Optional<VaultEditSession> stillOpen = adapter.findSession(open.get().sessionId());
        assertThat(stillOpen).isPresent();
        assertThat(stillOpen.get().state())
                .describedAs("a lease still running is left alone")
                .isEqualTo(VaultSessionState.ACTIVE);
        assertThat(adapter.findPage(ISLAND_ID, 2).orElseThrow().activeSessionId())
                .isEqualTo(open.get().sessionId());
    }

    @Test
    @DisplayName("A second sweep over the same rows closes nothing more")
    void theSweepIsIdempotent() {
        adapter.createPage(ISLAND_ID, 1, new byte[] {1}, OWNER_PROFILE.toString());
        assertThat(adapter.acquireEditSession(ISLAND_ID, 1, OWNER_UUID, Duration.ofMillis(5)))
                .isPresent();
        waitPast(Duration.ofMillis(5));

        IslandVaultService service = new IslandVaultService(adapter, null, 1, 10, Duration.ofSeconds(60));
        assertThat(service.closeExpiredSessions()).isEqualTo(1);
        assertThat(service.closeExpiredSessions())
                .describedAs("nothing left to close, and no row written twice")
                .isZero();
        assertThat(adapter.findExpiredActiveSessions()).isEmpty();
    }

    private static void waitPast(Duration lease) {
        try {
            Thread.sleep(lease.toMillis() + 60L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("appendAuditLog and findRecentAuditLogs order descending and respect limit")
    void auditLogsWork() {
        // The timestamps are explicit and a second apart. Two calls to create() land in the same
        // millisecond, and the driver stores a timestamp to the millisecond, so which of them came
        // first was down to the order the database happened to scan in.
        Instant when = Instant.parse("2026-09-01T00:00:00Z");
        VaultAuditLogEntry entry1 = new VaultAuditLogEntry(
                UUID.randomUUID(),
                ISLAND_ID,
                1,
                OWNER_PROFILE.toString(),
                VaultActionType.DEPOSIT,
                0,
                "GOLD_INGOT x32",
                32,
                when);
        VaultAuditLogEntry entry2 = new VaultAuditLogEntry(
                UUID.randomUUID(),
                ISLAND_ID,
                1,
                OWNER_PROFILE.toString(),
                VaultActionType.WITHDRAW,
                1,
                "IRON_INGOT x16",
                16,
                when.plusSeconds(1));

        adapter.appendAuditLog(entry1);
        adapter.appendAuditLog(entry2);

        List<VaultAuditLogEntry> logs = adapter.findRecentAuditLogs(ISLAND_ID, 10);
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).itemSummary()).isEqualTo("IRON_INGOT x16");
        assertThat(logs.get(1).itemSummary()).isEqualTo("GOLD_INGOT x32");

        List<VaultAuditLogEntry> singleLog = adapter.findRecentAuditLogs(ISLAND_ID, 1);
        assertThat(singleLog).hasSize(1);
        assertThat(singleLog.get(0).itemSummary()).isEqualTo("IRON_INGOT x16");
    }
}
