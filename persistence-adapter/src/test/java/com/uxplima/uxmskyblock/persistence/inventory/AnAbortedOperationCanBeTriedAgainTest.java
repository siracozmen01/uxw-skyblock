package com.uxplima.uxmskyblock.persistence.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationParticipantRecord;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
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
 * An operation the journal aborted can be tried again, and lands once.
 *
 * <p>An item reward journals its delivery under the reward's own operation id. A delivery that was
 * aborted, because its commit was refused or because recovery found it cut short by a crash, was
 * refused under that id forever after: the reward stayed in the inbox and could never be claimed. An
 * aborted attempt left nothing behind, so the next attempt starts afresh. A committed one is never
 * started again.
 */
class AnAbortedOperationCanBeTriedAgainTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");

    @TempDir
    Path dir;

    private Database database;
    private PlayerInventoryMutationJournalAdapter journal;
    private PlayerProfileInventoryAdapter inventories;
    private final PlayerUuid player = PlayerUuid.of(UUID.randomUUID());
    private final ProfileId profile = ProfileId.of(UUID.randomUUID());
    private long epoch;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("journal.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        journal = new PlayerInventoryMutationJournalAdapter(database);
        inventories = new PlayerProfileInventoryAdapter(database);
        SessionAuthorityOutcome session =
                new PlayerSessionAuthorityAdapter(database).ensureSession(player, profile, NODE);
        epoch = ((SessionAuthorityOutcome.Success) session).epoch();
    }

    @AfterEach
    void tearDown() {
        if (!database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("An aborted delivery is recorded again with what the inventory holds now, and commits once")
    void anAbortedOperationIsTriedAgain() {
        InventoryMutationOperationId delivery = InventoryMutationOperationId.random();
        long version = version();
        assertThat(intent(delivery, version, "before-1", "after-1").isSuccess()).isTrue();
        assertThat(journal.abortIntent(player, profile, NODE, epoch, delivery).isSuccess())
                .isTrue();

        assertThat(intent(delivery, version, "before-2", "after-2").isSuccess())
                .describedAs("the same delivery, tried again over an inventory that has changed")
                .isTrue();
        assertThat(journal.loadJournal(delivery).orElseThrow().state()).isEqualTo(InventoryMutationJournalState.INTENT);
        InventoryMutationParticipantRecord participant =
                journal.loadParticipant(delivery, 0).orElseThrow();
        assertThat(participant.beforeFingerprint()).isEqualTo("before-2");
        assertThat(participant.afterFingerprint()).isEqualTo("after-2");
        assertThat(participant.durableApplyState()).isEqualTo(ParticipantApplyState.PENDING);

        assertThat(journal.commitMutation(player, profile, NODE, epoch, version, delivery, new byte[] {7})
                        .version())
                .hasValue(version + 1);

        assertThat(intent(delivery, version + 1, "before-3", "after-3").isConflict())
                .describedAs("a committed delivery is never started again")
                .isTrue();
        assertThat(version()).isEqualTo(version + 1);
    }

    @Test
    @DisplayName("A retry that is refused leaves the aborted record where it was")
    void aRefusedRetryKeepsTheAbort() {
        InventoryMutationOperationId delivery = InventoryMutationOperationId.random();
        long version = version();
        assertThat(intent(delivery, version, "before-1", "after-1").isSuccess()).isTrue();
        assertThat(journal.abortIntent(player, profile, NODE, epoch, delivery).isSuccess())
                .isTrue();

        InventoryMutationJournalOutcome stale = journal.recordIntent(
                player,
                profile,
                NODE,
                epoch + 1,
                version,
                delivery,
                "REWARD_DELIVERY",
                "before-2",
                "after-2",
                "{}",
                Duration.ofMinutes(1));

        assertThat(stale.isSuccess()).isFalse();
        assertThat(journal.loadJournal(delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.ABORTED);
        assertThat(journal.loadParticipant(delivery, 0).orElseThrow().beforeFingerprint())
                .isEqualTo("before-1");
    }

    private InventoryMutationJournalOutcome intent(
            InventoryMutationOperationId operation, long version, String before, String after) {
        return journal.recordIntent(
                player,
                profile,
                NODE,
                epoch,
                version,
                operation,
                "REWARD_DELIVERY",
                before,
                after,
                "{}",
                Duration.ofMinutes(1));
    }

    private long version() {
        return inventories.loadInventory(profile).orElseThrow().version();
    }
}
