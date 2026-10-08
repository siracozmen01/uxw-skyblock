package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;

/**
 * Two players trading on node A: Ada gives three emeralds for Bo's diamond. Somewhere inside the trade
 * node A stops, and node B takes both players after.
 *
 * <p>An inventory is written as text so a test can say what it holds; the journal only ever sees its
 * fingerprint and, for putting a side back, its bytes.
 */
final class TradeCrashScene implements AutoCloseable {

    static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    static final ServerNodeId NODE_B = ServerNodeId.of("node-b");
    static final String ADA_BEFORE = "emerald x3, bread x1";
    static final String ADA_AFTER = "bread x1, diamond x1";
    static final String BO_BEFORE = "diamond x1";
    static final String BO_AFTER = "emerald x3";

    final Trader ada = new Trader(ADA_BEFORE, ADA_AFTER);
    final Trader bo = new Trader(BO_BEFORE, BO_AFTER);
    final InventoryMutationOperationId trade = InventoryMutationOperationId.random();
    final Database database;
    final PlayerSessionAuthorityAdapter sessions;
    final PlayerProfileInventoryAdapter inventories;
    final PlayerTradeJournalAdapter journal;
    final TradeJournalRecovery recovery;

    /** One side of the trade and the session it plays under. */
    final class Trader {
        final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
        final ProfileId profile = ProfileId.of(UUID.randomUUID());
        final String before;
        final String after;
        long epoch;

        Trader(String before, String after) {
            this.before = before;
            this.after = after;
        }

        TradeJournalPort.Holder holder() {
            return new TradeJournalPort.Holder(player, profile, epoch);
        }

        TradeJournalPort.Side side() {
            return new TradeJournalPort.Side(
                    holder(),
                    version(),
                    InventoryFingerprint.of(bytes(before)),
                    InventoryFingerprint.of(bytes(after)),
                    bytes(before));
        }

        TradeJournalPort.Outcome outcome() {
            return new TradeJournalPort.Outcome(holder(), version(), bytes(after));
        }

        long version() {
            return inventories.loadInventory(profile).orElseThrow().version();
        }

        String inventory() {
            return new String(inventories.loadInventory(profile).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
        }

        /** Writes {@code contents} as the inventory, the way an ambient checkpoint does. */
        InventoryMutationJournalOutcome checkpoint(ServerNodeId node, String contents) {
            return inventories
                            .checkpointInventory(
                                    player,
                                    profile,
                                    node,
                                    epoch,
                                    version(),
                                    ProfileInventoryRecord.createDefault(profile, bytes(contents), new byte[0]))
                            .isSuccess()
                    ? InventoryMutationJournalOutcome.success()
                    : InventoryMutationJournalOutcome.rejected("CHECKPOINT_REFUSED");
        }

        /**
         * The player leaves node A with {@code contents} in hand. The final write is not an ambient
         * checkpoint and does not wait for an open trade, so what the player held is what is kept.
         */
        void leavesWith(String contents) {
            assertThat(sessions.drain(player, NODE_A, epoch).isSuccess()).isTrue();
            assertThat(new PlayerProfileHandoffFinalizationAdapter(database)
                            .finalizeHandoffFlush(
                                    player,
                                    profile,
                                    NODE_A,
                                    epoch,
                                    version(),
                                    ProfileInventoryRecord.createDefault(profile, bytes(contents), new byte[0]))
                            .isSuccess())
                    .isTrue();
        }

        /** Node A is gone; node B takes this player once the lease runs out. */
        void comesToNodeB() throws Exception {
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

        List<TradeJournalRecovery.Settled> recoversOnNodeB() {
            return recovery.recover(holder(), NODE_B);
        }
    }

    TradeCrashScene(Path dir) {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("trade.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        sessions = new PlayerSessionAuthorityAdapter(database);
        inventories = new PlayerProfileInventoryAdapter(database);
        journal = new PlayerTradeJournalAdapter(database);
        recovery = new TradeJournalRecovery(journal, inventories);
        for (Trader trader : List.of(ada, bo)) {
            trader.epoch = epochOf(sessions.ensureSession(trader.player, trader.profile, NODE_A));
            assertThat(trader.checkpoint(NODE_A, trader.before).isSuccess()).isTrue();
        }
    }

    /** Node A records the trade's intent, both sides at once. */
    InventoryMutationJournalOutcome intentOnA() {
        return journal.recordIntent(trade, NODE_A, List.of(ada.side(), bo.side()), "{}", Duration.ofMinutes(1));
    }

    /** How many journals the database holds. */
    int journals() throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM inventory_mutation_journals");
                var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static byte[] bytes(String contents) {
        return contents.getBytes(StandardCharsets.UTF_8);
    }

    private static long epochOf(SessionAuthorityOutcome outcome) {
        assertThat(outcome.isSuccess()).isTrue();
        return ((SessionAuthorityOutcome.Success) outcome).epoch();
    }

    @Override
    public void close() {
        if (!database.isClosed()) {
            database.close();
        }
    }
}
