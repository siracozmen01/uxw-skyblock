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
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.session.PlayerSessionAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;

/**
 * A journaled delivery on node A, a crash somewhere inside it, and node B taking the player after.
 *
 * <p>The inventory is written as text so a test can say what it holds; the journal only ever sees its
 * fingerprint, the same one a real delivery records.
 */
final class JournalCrashScene implements AutoCloseable {

    static final ServerNodeId NODE_A = ServerNodeId.of("node-a");
    static final ServerNodeId NODE_B = ServerNodeId.of("node-b");
    static final String BEFORE = "cobblestone x3";
    static final String AFTER = "cobblestone x3, diamond x1";

    final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    final ProfileId profile = ProfileId.of(UUID.randomUUID());
    final InventoryMutationOperationId delivery = InventoryMutationOperationId.random();
    final Database database;
    final PlayerSessionAuthorityAdapter sessions;
    final PlayerProfileInventoryAdapter inventories;
    final PlayerInventoryMutationJournalAdapter journal;
    final InventoryJournalRecovery recovery;
    final long epochOnA;

    JournalCrashScene(Path dir) {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("journal.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        sessions = new PlayerSessionAuthorityAdapter(database);
        inventories = new PlayerProfileInventoryAdapter(database);
        journal = new PlayerInventoryMutationJournalAdapter(database);
        recovery = new InventoryJournalRecovery(journal, inventories);
        epochOnA = epochOf(sessions.ensureSession(player, profile, NODE_A));
        write(NODE_A, epochOnA, BEFORE);
    }

    /** Node A records its intent to turn {@link #BEFORE} into {@link #AFTER}. */
    void intentOnA() {
        assertThat(journal.recordIntent(
                                player,
                                profile,
                                NODE_A,
                                epochOnA,
                                version(),
                                delivery,
                                "REWARD_DELIVERY",
                                InventoryFingerprint.of(bytes(BEFORE)),
                                InventoryFingerprint.of(bytes(AFTER)),
                                "{}",
                                Duration.ofMinutes(1))
                        .isSuccess())
                .isTrue();
    }

    /** Writes {@code contents} as the inventory, the way an ambient checkpoint does. */
    void write(ServerNodeId node, long epoch, String contents) {
        assertThat(inventories
                        .checkpointInventory(player, profile, node, epoch, version(), bytes(contents))
                        .isSuccess())
                .isTrue();
    }

    /** Node A is gone; node B takes the player once its lease runs out, and is ready to play. */
    long nodeBTakesOver() throws Exception {
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE player_sessions SET lease_expires_at = DATETIME('now', '-30 seconds')"
                                + " WHERE player_uuid = ?")) {
            ps.setString(1, player.value().toString());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        long epoch = epochOf(sessions.ensureSession(player, profile, NODE_B));
        assertThat(sessions.markRecoveredActive(player, NODE_B, epoch).isSuccess())
                .isTrue();
        return epoch;
    }

    List<InventoryJournalRecovery.Settled> recoverOn(ServerNodeId node, long epoch) {
        return recovery.recover(player, profile, node, epoch);
    }

    /** Node B delivers the same operation again and commits it. */
    InventoryMutationJournalOutcome deliverAgainOnB(long epoch) {
        InventoryMutationJournalOutcome intent = journal.recordIntent(
                player,
                profile,
                NODE_B,
                epoch,
                version(),
                delivery,
                "REWARD_DELIVERY",
                InventoryFingerprint.of(bytes(inventory())),
                InventoryFingerprint.of(bytes(AFTER)),
                "{}",
                Duration.ofMinutes(1));
        if (!intent.isSuccess()) {
            return intent;
        }
        return journal.commitMutation(player, profile, NODE_B, epoch, version(), delivery, bytes(AFTER));
    }

    long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }

    String inventory() {
        return new String(inventories.loadInventory(profile).orElseThrow().inventoryNbt(), StandardCharsets.UTF_8);
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
