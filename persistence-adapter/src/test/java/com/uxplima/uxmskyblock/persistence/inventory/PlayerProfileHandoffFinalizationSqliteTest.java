package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fast-lane SQLite test suite for {@link PlayerProfileHandoffFinalizationAdapter} (WP2-004).
 *
 * <p>Verifies handoff finalization durability contract, optimistic concurrency control (OCC),
 * session authority fencing under DRAINING state, active-profile binding to locked session,
 * atomic synchronization of {@code profile_inventories.profile_inventory_version} with
 * {@code player_sessions.last_durable_inventory_version}, crash-window recoverability,
 * trigger-based transaction rollback without production callbacks, and SQLite writer
 * serialization via {@code BEGIN IMMEDIATE}.
 */
class PlayerProfileHandoffFinalizationSqliteTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-alpha");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-bravo");

    private Database database;
    private PlayerProfileInventoryAdapter inventoryAdapter;
    private PlayerProfileHandoffFinalizationAdapter finalizationAdapter;
    private PlayerSessionAuthorityAdapter sessionAdapter;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        inventoryAdapter = new PlayerProfileInventoryAdapter(database);
        finalizationAdapter = new PlayerProfileHandoffFinalizationAdapter(database);
        sessionAdapter = new PlayerSessionAuthorityAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("1. Valid DRAINING authority + expected version succeeds and atomically advances both versions")
    void validDrainingAuthoritySucceeds() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};
        byte[] finalNbt = new byte[] {4, 5, 6};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9, 9}));

        // Initial state assertions
        Optional<ProfileInventoryRecord> initialInv = inventoryAdapter.loadInventory(profile);
        assertThat(initialInv).isPresent();
        assertThat(initialInv.get().version()).isEqualTo(1L);
        assertThat(initialInv.get().inventoryNbt()).isEqualTo(initialNbt);

        OptionalLong initialMarker = finalizationAdapter.loadLastDurableInventoryVersion(player);
        assertThat(initialMarker).hasValue(1L);

        // Execute handoff finalization flush
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, finalNbt);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(outcome).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        // Durable inventory verification: version == 2
        Optional<ProfileInventoryRecord> loadedInv = inventoryAdapter.loadInventory(profile);
        assertThat(loadedInv).isPresent();
        assertThat(loadedInv.get().version()).isEqualTo(2L);
        assertThat(loadedInv.get().inventoryNbt()).isEqualTo(finalNbt);

        // Durable session marker verification: last_durable_inventory_version == 2
        OptionalLong loadedMarker = finalizationAdapter.loadLastDurableInventoryVersion(player);
        assertThat(loadedMarker).hasValue(2L);
    }

    @Test
    @DisplayName("2. Subsequent valid mutation increments version to 3 and advances session marker to 3")
    void monotonicVersionIncrement() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

        // 1 -> 2
        ProfileInventoryMutationOutcome first =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {10});
        assertThat(first).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // 2 -> 3
        ProfileInventoryMutationOutcome second =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 2L, new byte[] {20});
        assertThat(second).isEqualTo(ProfileInventoryMutationOutcome.success(3L));
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(3L);

        Optional<ProfileInventoryRecord> loaded = inventoryAdapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(3L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(new byte[] {20});
    }

    @Test
    @DisplayName("3. Stale OCC version is rejected and leaves both inventory and session marker unchanged")
    void staleVersionIsRejectedWithoutModifyingState() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Call with stale expected version 99L (actual version is 1L)
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 99L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();

        // State remains unchanged
        Optional<ProfileInventoryRecord> loaded = inventoryAdapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(1L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(initialNbt);

        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName("4. Wrong node is rejected and leaves both rows unchanged")
    void wrongNodeIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Calling from NODE_B while authoritative_node is NODE_A
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_B, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName("5. Stale session epoch is rejected and leaves both rows unchanged")
    void staleEpochIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 2L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Calling with stale epoch 1L while current epoch is 2L
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName(
            "6. Cross-profile mismatch is rejected: Player A session with active Profile A cannot mutate Profile B")
    void crossProfileMismatchIsRejected() {
        PlayerUuid playerA = PlayerUuid.of(UUID.randomUUID());
        ProfileId profileA = ProfileId.of(UUID.randomUUID());
        ProfileId profileB = ProfileId.of(UUID.randomUUID());
        byte[] nbtA = new byte[] {1, 1, 1};
        byte[] nbtB = new byte[] {2, 2, 2};

        // Seed playerA with active_profile_id = profileA in DRAINING state
        seedSession(database, playerA, profileA, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profileA, nbtA, new byte[] {0}));

        // Seed a second profile B belonging to playerA or another player with a valid inventory row at version 1
        seedSecondProfile(database, playerA, profileB);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profileB, nbtB, new byte[] {0}));

        // Calling finalization for playerA session but targeting profileB
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(playerA, profileB, NODE_A, 1L, 1L, new byte[] {9, 9, 9});

        assertThat(outcome.isRejected()).isTrue();

        // Verify Profile A unchanged
        Optional<ProfileInventoryRecord> loadedA = inventoryAdapter.loadInventory(profileA);
        assertThat(loadedA).isPresent();
        assertThat(loadedA.get().version()).isEqualTo(1L);
        assertThat(loadedA.get().inventoryNbt()).isEqualTo(nbtA);

        // Verify Profile B unchanged
        Optional<ProfileInventoryRecord> loadedB = inventoryAdapter.loadInventory(profileB);
        assertThat(loadedB).isPresent();
        assertThat(loadedB.get().version()).isEqualTo(1L);
        assertThat(loadedB.get().inventoryNbt()).isEqualTo(nbtB);

        // Verify Player A session marker unchanged
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(playerA)).hasValue(1L);
    }

    @Test
    @DisplayName("7. ACTIVE state is rejected by finalization adapter (DRAINING-only enforcement)")
    void activeStateIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Finalization adapter MUST reject ACTIVE sessions
        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName("8. HANDOFF_READY state is rejected by finalization adapter")
    void handoffReadyStateIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());

        seedSession(database, player, profile, NODE_A, 1L, "HANDOFF_READY", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName("9. RECOVERING and OFFLINE states are rejected by finalization adapter")
    void recoveringAndOfflineStatesAreRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());

        seedSession(database, player, profile, NODE_A, 1L, "RECOVERING", false, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

        assertThat(finalizationAdapter
                        .finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        setSessionState(database, player, "OFFLINE");
        assertThat(finalizationAdapter
                        .finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99})
                        .isRejected())
                .isTrue();

        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName("10. Expired lease is rejected by finalization adapter")
    void expiredLeaseIsRejected() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", true, 1L);
        inventoryAdapter.initializeInventory(
                ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

        ProfileInventoryMutationOutcome outcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99});

        assertThat(outcome.isRejected()).isTrue();
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(1L);
    }

    @Test
    @DisplayName(
            "11. Transaction rollback via failure injection: failure on session marker update rolls back profile inventory")
    void transactionRollbackPreservesBothRowsWithoutProductionCallback() throws SQLException {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] initialNbt = new byte[] {1, 2, 3};

        seedSession(database, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, initialNbt, new byte[] {9}));

        // Install a test-only trigger on player_sessions to fail the second statement (session marker update)
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TRIGGER fail_session_marker BEFORE UPDATE OF last_durable_inventory_version ON player_sessions "
                            + "BEGIN "
                            + "  SELECT RAISE(ABORT, 'Simulated failure during session marker update'); "
                            + "END;");
        }

        try {
            // Call normal production method with NO test callback
            assertThatThrownBy(() ->
                            finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {99}))
                    .isInstanceOf(InventoryPersistenceException.class);

            // Verify both tables were rolled back cleanly
            assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(1L);
            assertThat(inventoryAdapter.loadInventory(profile).get().inventoryNbt())
                    .isEqualTo(initialNbt);
            assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player))
                    .hasValue(1L);
        } finally {
            try (Connection conn = database.connection();
                    Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TRIGGER IF EXISTS fail_session_marker;");
            }
        }
    }

    @Test
    @DisplayName("12. SQLite writer serialization via BEGIN IMMEDIATE with temporal pre-release verification")
    void sqliteWriterSerialization(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("finalization-serialization.db");
        try (Database fileDb = DatabaseTestFixture.createSqliteFile(dbFile)) {
            new MigrationRunner(fileDb).apply(SkyblockMigrations.getMigrations(fileDb.dialect()));
            PlayerProfileInventoryAdapter fileInvAdapter = new PlayerProfileInventoryAdapter(fileDb);
            PlayerProfileHandoffFinalizationAdapter fileFinalAdapter =
                    new PlayerProfileHandoffFinalizationAdapter(fileDb);

            PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
            ProfileId profile = ProfileId.of(UUID.randomUUID());

            seedSession(fileDb, player, profile, NODE_A, 1L, "DRAINING", false, 1L);
            fileInvAdapter.initializeInventory(
                    ProfileInventoryRecord.createDefault(profile, new byte[] {1}, new byte[] {2}));

            String jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch txAWriterAcquired = new CountDownLatch(1);
            CountDownLatch txBAttemptStarted = new CountDownLatch(1);
            CountDownLatch releaseTxA = new CountDownLatch(1);

            try (Connection connA = java.sql.DriverManager.getConnection(jdbcUrl)) {
                // TxA: raw test connection acquires BEGIN IMMEDIATE exclusive writer lock on database file
                Future<Boolean> txAFuture = executor.submit(() -> {
                    try (Statement stmt = connA.createStatement()) {
                        stmt.execute("BEGIN IMMEDIATE");
                        txAWriterAcquired.countDown();
                        boolean released = releaseTxA.await(5, TimeUnit.SECONDS);
                        if (!released) {
                            throw new RuntimeException("Timeout waiting for releaseTxA latch");
                        }
                        stmt.execute("ROLLBACK");
                        return true;
                    }
                });

                assertThat(txAWriterAcquired.await(5, TimeUnit.SECONDS))
                        .as("TxA must acquire BEGIN IMMEDIATE writer lock")
                        .isTrue();

                // TxB: calls the REAL production finalizeHandoffFlush (competing writer)
                Future<ProfileInventoryMutationOutcome> txBFuture = executor.submit(() -> {
                    txBAttemptStarted.countDown();
                    return fileFinalAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {10});
                });

                assertThat(txBAttemptStarted.await(5, TimeUnit.SECONDS))
                        .as("TxB must signal attempt started")
                        .isTrue();

                // TEMPORAL PRE-RELEASE PROOF:
                // While TxA holds BEGIN IMMEDIATE, TxB must either remain blocked or fail with busy.
                // It MUST NOT acquire and commit writer authority!
                boolean txBCompletedPrematurely = false;
                try {
                    ProfileInventoryMutationOutcome prematureResult = txBFuture.get(300, TimeUnit.MILLISECONDS);
                    assertThat(prematureResult.isSuccess())
                            .as("TxB must NOT commit writer authority while TxA holds BEGIN IMMEDIATE")
                            .isFalse();
                    txBCompletedPrematurely = true;
                } catch (TimeoutException e) {
                    // Allowed outcome A: TxB is blocked waiting for writer lock
                    assertThat(txBFuture.isDone()).isFalse();
                } catch (ExecutionException e) {
                    // Allowed outcome B: SQLite busy error rejected the competing writer
                    assertThat(e.getCause()).isInstanceOf(InventoryPersistenceException.class);
                    txBCompletedPrematurely = true;
                }

                // Release TxA so it relinquishes the exclusive writer lock
                releaseTxA.countDown();
                assertThat(txAFuture.get(5, TimeUnit.SECONDS)).isTrue();

                // After TxA releases, prove database remains usable and final canonical state is correct
                if (!txBCompletedPrematurely) {
                    ProfileInventoryMutationOutcome finalResult = txBFuture.get(5, TimeUnit.SECONDS);
                    assertThat(finalResult.isSuccess()).isTrue();
                    assertThat(finalResult).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
                    assertThat(fileFinalAdapter.loadLastDurableInventoryVersion(player))
                            .hasValue(2L);
                } else {
                    // If TxB failed with busy, subsequent call now succeeds cleanly
                    ProfileInventoryMutationOutcome retryResult =
                            fileFinalAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, new byte[] {10});
                    assertThat(retryResult.isSuccess()).isTrue();
                    assertThat(retryResult).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
                    assertThat(fileFinalAdapter.loadLastDurableInventoryVersion(player))
                            .hasValue(2L);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName(
            "13. Crash window recovery: crash in DRAINING with committed inventory is fully recoverable via failureTakeover")
    void crashWindowRecoveryPreservesCommittedVersion() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] v2FinalNbt = new byte[] {4, 5, 6};

        // Node A is actively running session at epoch 1
        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {10}));

        // Step 1: Node A begins handoff drain
        SessionAuthorityOutcome drainOutcome = sessionAdapter.drain(player, NODE_A, 1L);
        assertThat(drainOutcome.isSuccess()).isTrue();

        // Step 2: Node A executes final durable inventory flush
        ProfileInventoryMutationOutcome flushOutcome =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, v2FinalNbt);
        assertThat(flushOutcome.isSuccess()).isTrue();
        assertThat(flushOutcome).isEqualTo(ProfileInventoryMutationOutcome.success(2L));

        // Invariant check: latest durable inventory version in both places is 2
        assertThat(inventoryAdapter.loadInventory(profile).get().version()).isEqualTo(2L);
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // Step 3: SIMULATED CRASH of Node A!
        // Node A crashes before calling prepareHandoff().
        // The session state in SQL remains 'DRAINING' with last_durable_inventory_version = 2.
        // Node A stops heartbeating, so lease expires:
        expireSession(database, player);

        // Step 4: Node B detects crash via expired lease and executes failure takeover
        SessionAuthorityOutcome takeoverOutcome = sessionAdapter.failureTakeover(player, 1L, NODE_B);
        assertThat(takeoverOutcome.isSuccess()).isTrue();
        assertThat(takeoverOutcome).isEqualTo(SessionAuthorityOutcome.success(2L, true)); // Epoch increments 1 -> 2, recovering=true

        // Step 5: Node B loads durable inventory state matching last_durable_inventory_version
        OptionalLong sessionMarker = finalizationAdapter.loadLastDurableInventoryVersion(player);
        assertThat(sessionMarker).hasValue(2L);

        Optional<ProfileInventoryRecord> restoredRecord = inventoryAdapter.loadInventory(profile);
        assertThat(restoredRecord).isPresent();
        assertThat(restoredRecord.get().version()).isEqualTo(sessionMarker.getAsLong());
        assertThat(restoredRecord.get().inventoryNbt()).isEqualTo(v2FinalNbt);
    }

    @Test
    @DisplayName(
            "14. Full canonical handoff protocol: DRAIN -> final durable flush -> prepareHandoff -> plannedAcquire")
    void fullCanonicalHandoffProtocol() {
        PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        ProfileId profile = ProfileId.of(UUID.randomUUID());
        String handoffId = UUID.randomUUID().toString();
        byte[] v1Nbt = new byte[] {1, 2, 3};
        byte[] finalNbt = new byte[] {9, 8, 7};

        seedSession(database, player, profile, NODE_A, 1L, "ACTIVE", false, 1L);
        inventoryAdapter.initializeInventory(ProfileInventoryRecord.createDefault(profile, v1Nbt, new byte[] {0}));

        // 1. DRAIN on Node A
        SessionAuthorityOutcome drain = sessionAdapter.drain(player, NODE_A, 1L);
        assertThat(drain.isSuccess()).isTrue();

        // 2. Final durable inventory flush on Node A (commits version 2, updates last_durable = 2)
        ProfileInventoryMutationOutcome flush =
                finalizationAdapter.finalizeHandoffFlush(player, profile, NODE_A, 1L, 1L, finalNbt);
        assertThat(flush.isSuccess()).isTrue();
        assertThat(flush).isEqualTo(ProfileInventoryMutationOutcome.success(2L));
        assertThat(finalizationAdapter.loadLastDurableInventoryVersion(player)).hasValue(2L);

        // 3. prepareHandoff on Node A (binds handoffId, target NODE_B, transitions to HANDOFF_READY)
        SessionAuthorityOutcome prepare = sessionAdapter.prepareHandoff(player, NODE_A, 1L, handoffId, NODE_B);
        assertThat(prepare.isSuccess()).isTrue();

        // 4. plannedAcquire on Node B (claims session, advances epoch 1 -> 2, transitions back to ACTIVE)
        SessionAuthorityOutcome acquire = sessionAdapter.plannedAcquire(player, NODE_A, 1L, handoffId, NODE_B);
        assertThat(acquire.isSuccess()).isTrue();
        assertThat(acquire).isEqualTo(SessionAuthorityOutcome.success(2L));

        // 5. Node B loads durable inventory matching last_durable_inventory_version (2)
        OptionalLong marker = finalizationAdapter.loadLastDurableInventoryVersion(player);
        assertThat(marker).hasValue(2L);

        Optional<ProfileInventoryRecord> loaded = inventoryAdapter.loadInventory(profile);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().version()).isEqualTo(2L);
        assertThat(loaded.get().inventoryNbt()).isEqualTo(finalNbt);
    }

    // ==========================================
    // Fixture Helpers
    // ==========================================

    private static void seedSession(
            Database database,
            PlayerUuid player,
            ProfileId profile,
            ServerNodeId node,
            long epoch,
            String state,
            boolean expired,
            long lastDurableVersion) {
        try (Connection conn = database.connection()) {
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys = ON;");
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, player.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                ps.setString(1, profile.value().toString());
                ps.setString(2, player.value().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("UPDATE player_accounts SET active_profile_id = ? WHERE player_uuid = ?")) {
                ps.setString(1, profile.value().toString());
                ps.setString(2, player.value().toString());
                ps.executeUpdate();
            }

            String leaseExpr = expired ? "DATETIME('now', '-10 seconds')" : "DATETIME('now', '+60 seconds')";
            String sql = "INSERT INTO player_sessions ("
                    + "player_uuid, active_profile_id, authoritative_node, session_epoch, "
                    + "state, last_durable_inventory_version, lease_expires_at"
                    + ") VALUES (?, ?, ?, ?, ?, ?, " + leaseExpr + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.value().toString());
                ps.setString(2, profile.value().toString());
                ps.setString(3, node.value());
                ps.setLong(4, epoch);
                ps.setString(5, state);
                ps.setLong(6, lastDurableVersion);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed SQLite test session", e);
        }
    }

    private static void seedSecondProfile(Database database, PlayerUuid player, ProfileId profile) {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
            ps.setString(1, profile.value().toString());
            ps.setString(2, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to seed second profile", e);
        }
    }

    private static void setSessionState(Database database, PlayerUuid player, String newState) {
        try (Connection conn = database.connection();
                PreparedStatement ps =
                        conn.prepareStatement("UPDATE player_sessions SET state = ? WHERE player_uuid = ?")) {
            ps.setString(1, newState);
            ps.setString(2, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set session state", e);
        }
    }

    private static void expireSession(Database database, PlayerUuid player) {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = DATETIME('now', '-10 seconds') WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to expire session", e);
        }
    }
}
