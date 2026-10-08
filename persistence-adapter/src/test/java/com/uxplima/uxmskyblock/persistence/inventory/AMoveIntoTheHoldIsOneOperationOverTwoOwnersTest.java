package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.inventory.CargoJournalRecovery;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import com.uxplima.uxmskyblock.persistence.tradewinds.SqlVesselsAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ada puts three emeralds into her vessel's hold. Her inventory is hers, written under her session; the
 * hold is the vessel's, written under its lease. The move is one operation over both, and nothing the
 * owners do in between, nor a node lost in the middle, leaves the emeralds in both places or in neither.
 */
class AMoveIntoTheHoldIsOneOperationOverTwoOwnersTest {

    private static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    private static final ServerNodeId NODE_B = ServerNodeId.of("node-b");
    private static final String ADA_BEFORE = "emerald x3, bread x1";
    private static final String ADA_AFTER = "bread x1";
    private static final String HOLD_BEFORE = "";
    private static final String HOLD_AFTER = "emerald x3";

    @TempDir
    Path dir;

    private final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    private final ProfileId profile = ProfileId.of(UUID.randomUUID());
    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final InventoryMutationOperationId move = InventoryMutationOperationId.random();

    @SuppressWarnings("NullAway.Init")
    private Database database;

    @SuppressWarnings("NullAway.Init")
    private PlayerSessionAuthorityAdapter sessions;

    @SuppressWarnings("NullAway.Init")
    private PlayerProfileInventoryAdapter inventories;

    @SuppressWarnings("NullAway.Init")
    private VesselCargoJournalAdapter journal;

    @SuppressWarnings("NullAway.Init")
    private SqlVesselsAdapter vessels;

    @SuppressWarnings("NullAway.Init")
    private RootAuthorityAdapter leases;

    private long epoch;
    private long leaseEpoch;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("hold.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('" + vessel.value()
                    + "', '" + profile.value() + "', '" + player.value() + "')");
        }
        sessions = new PlayerSessionAuthorityAdapter(database);
        inventories = new PlayerProfileInventoryAdapter(database);
        journal = new VesselCargoJournalAdapter(database);
        vessels = new SqlVesselsAdapter(database.dataSource());
        leases = new RootAuthorityAdapter(database);
        vessels.add(vessel);
        epoch = epochOf(sessions.ensureSession(player, profile, NODE_A));
        assertThat(inventories
                        .checkpointInventory(
                                player,
                                profile,
                                NODE_A,
                                epoch,
                                version(),
                                ProfileInventoryRecord.createDefault(profile, bytes(ADA_BEFORE), new byte[0]))
                        .isSuccess())
                .isTrue();
        leaseEpoch = epochOf(leases.acquire(VesselLease.rootOf(vessel), NODE_A, 30));
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("Intent and commit write the inventory and the hold together, each over its own version")
    void theMoveIsWritten() {
        long before = version();

        assertThat(journal.recordIntent(move, NODE_A, move(before, 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        assertThat(hold()).describedAs("the intent leaves the hold alone").isEqualTo(HOLD_BEFORE);
        assertThat(journal.markApplied(move, 0).isSuccess()).isTrue();
        assertThat(journal.commit(move, NODE_A, move(before, 1)).isSuccess()).isTrue();

        assertThat(journal.state(move)).contains(InventoryMutationJournalState.COMMITTED);
        assertThat(inventory()).isEqualTo(ADA_AFTER);
        assertThat(version()).isEqualTo(before + 2);
        assertThat(hold()).isEqualTo(HOLD_AFTER);
        assertThat(vessels.cargo(vessel).orElseThrow().version()).isEqualTo(2L);
        assertThat(journal.findOpenMoves(profile)).isEmpty();
    }

    @Test
    @DisplayName("No node writes a hold whose lease it does not hold, and no move is written over a moved hold")
    void onlyTheLeaseholderWritesTheHold() throws Exception {
        long before = version();
        assertThat(journal.recordIntent(move, NODE_A, move(before, 2), Duration.ofMinutes(1))
                        .isSuccess())
                .describedAs("a hold at another version")
                .isFalse();
        assertThat(journal.recordIntent(
                                move,
                                NODE_A,
                                new CargoJournalPort.Move(
                                        holder(),
                                        before,
                                        bytes(ADA_BEFORE),
                                        bytes(ADA_AFTER),
                                        new CargoJournalPort.Hold(vessel, leaseEpoch + 1),
                                        1,
                                        bytes(HOLD_BEFORE),
                                        bytes(HOLD_AFTER)),
                                Duration.ofMinutes(1))
                        .isSuccess())
                .describedAs("an old lease epoch")
                .isFalse();

        assertThat(journal.recordIntent(move, NODE_A, move(before, 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        expireLease();
        assertThat(journal.commit(move, NODE_A, move(before, 1)).isSuccess())
                .describedAs("a lease that ran out")
                .isFalse();
        assertThat(hold()).isEqualTo(HOLD_BEFORE);
        assertThat(journal.abort(move, NODE_A, holder()).isSuccess()).isTrue();
        assertThat(inventory()).isEqualTo(ADA_BEFORE);
        assertThat(version()).describedAs("the intent's version stands").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("Once the intent stands, a last write at the version the session knew is refused")
    void aLastWriteMidMoveIsRefused() {
        long known = version();
        assertThat(journal.recordIntent(move, NODE_A, move(known, 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        assertThat(sessions.drain(player, NODE_A, epoch).isSuccess()).isTrue();

        assertThat(new PlayerProfileHandoffFinalizationAdapter(database)
                        .finalizeHandoffFlush(
                                player,
                                profile,
                                NODE_A,
                                epoch,
                                known,
                                ProfileInventoryRecord.createDefault(profile, bytes(ADA_AFTER), new byte[0]))
                        .isSuccess())
                .isFalse();
        assertThat(inventory()).isEqualTo(ADA_BEFORE);
    }

    @Test
    @DisplayName("A move cut short before the player changed is aborted, and the hold was never touched")
    void cutShortBeforeThePlayer() throws Exception {
        assertThat(journal.recordIntent(move, NODE_A, move(version(), 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        comesToNodeB();

        assertThat(recovery().recover(holder(), NODE_B))
                .containsExactly(new CargoJournalRecovery.Settled(move, CargoJournalRecovery.Settlement.ABORTED));
        assertThat(inventory()).isEqualTo(ADA_BEFORE);
        assertThat(hold()).isEqualTo(HOLD_BEFORE);
        assertThat(journal.findOpenMoves(profile)).isEmpty();
    }

    @Test
    @DisplayName("A player whose half reached durable storage before the commit gets it put back")
    void cutShortAfterThePlayer() throws Exception {
        long known = version();
        assertThat(journal.recordIntent(move, NODE_A, move(known, 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        // What only a write over the intent's version could do: the player's half lands, the hold's never.
        overwriteInventory(ADA_AFTER);
        comesToNodeB();

        assertThat(recovery().recover(holder(), NODE_B))
                .containsExactly(new CargoJournalRecovery.Settled(move, CargoJournalRecovery.Settlement.PUT_BACK));
        assertThat(inventory()).isEqualTo(ADA_BEFORE);
        assertThat(hold()).isEqualTo(HOLD_BEFORE);
    }

    @Test
    @DisplayName("An inventory that is neither before nor after is quarantined, and nothing is given or taken")
    void neitherIsQuarantined() throws Exception {
        assertThat(journal.recordIntent(move, NODE_A, move(version(), 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
        overwriteInventory("dirt x64");
        comesToNodeB();

        assertThat(recovery().recover(holder(), NODE_B))
                .containsExactly(new CargoJournalRecovery.Settled(move, CargoJournalRecovery.Settlement.QUARANTINED));
        assertThat(journal.state(move)).contains(InventoryMutationJournalState.RECOVERY_REQUIRED);
        assertThat(inventory()).isEqualTo("dirt x64");
        assertThat(hold()).isEqualTo(HOLD_BEFORE);
    }

    @Test
    @DisplayName("Another node cannot settle a move under a session it does not hold")
    void recoveryNeedsTheSession() {
        assertThat(journal.recordIntent(move, NODE_A, move(version(), 1), Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();

        assertThat(recovery().recover(holder(), NODE_B))
                .containsExactly(new CargoJournalRecovery.Settled(move, CargoJournalRecovery.Settlement.REFUSED));
        assertThat(journal.state(move)).contains(InventoryMutationJournalState.INTENT);
    }

    private CargoJournalRecovery recovery() {
        return new CargoJournalRecovery(journal, inventories);
    }

    private CargoJournalPort.Move move(long playerVersion, long cargoVersion) {
        return new CargoJournalPort.Move(
                holder(),
                playerVersion,
                bytes(ADA_BEFORE),
                bytes(ADA_AFTER),
                new CargoJournalPort.Hold(vessel, leaseEpoch),
                cargoVersion,
                bytes(HOLD_BEFORE),
                bytes(HOLD_AFTER));
    }

    private TradeJournalPort.Holder holder() {
        return new TradeJournalPort.Holder(player, profile, epoch);
    }

    private long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }

    private String inventory() {
        return new String(inventories.loadInventory(profile).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
    }

    private String hold() {
        return new String(vessels.cargo(vessel).orElseThrow().items(), StandardCharsets.UTF_8);
    }

    private void overwriteInventory(String contents) throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE profile_inventories SET inventory_nbt = ? WHERE profile_id = ?")) {
            ps.setBytes(1, bytes(contents));
            ps.setString(2, profile.value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private void expireLease() throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE mode_owned_authorities SET lease_expires_at = DATETIME('now', '-30 seconds')")) {
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private void comesToNodeB() throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = DATETIME('now', '-30 seconds')"
                                + " WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        epoch = epochOf(sessions.ensureSession(player, profile, NODE_B));
        assertThat(sessions.markRecoveredActive(player, NODE_B, epoch).isSuccess())
                .isTrue();
    }

    private static long epochOf(SessionAuthorityOutcome outcome) {
        assertThat(outcome.isSuccess()).isTrue();
        return ((SessionAuthorityOutcome.Success) outcome).epoch();
    }

    private static long epochOf(IslandAuthorityOutcome outcome) {
        assertThat(outcome.isSuccess()).isTrue();
        return ((IslandAuthorityOutcome.Success) outcome).epoch();
    }

    private static byte[] bytes(String contents) {
        return contents.getBytes(StandardCharsets.UTF_8);
    }
}
