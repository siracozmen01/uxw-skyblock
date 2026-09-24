package com.uxplima.uxmskyblock.persistence.inventory;

import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.NODE_A;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.NODE_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery.Settlement;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A crash after the inventory changed in memory and before the journal committed is settled by what
 * reached durable storage.
 *
 * <p>The testing standard names this test. Node A writes the intent, changes the inventory in memory
 * and stops before the commit. What node B finds decides the rest. If the change died with node A, the
 * inventory is as the intent found it and the intent is aborted. If an ambient checkpoint wrote the
 * change first, the inventory already holds the outcome: the operation is committed as it stands and
 * never delivered again. If the inventory holds neither, the operation is quarantined and nothing is
 * refunded. The standard reverts the second case; the journal keeps fingerprints, not inventories, so
 * there is nothing to revert to, and {@link InventoryJournalRecovery} says why the outcome is kept.
 */
class InventoryJournalCrashAfterApplyBeforeCommitTest {

    @TempDir
    Path dir;

    private JournalCrashScene scene;

    @BeforeEach
    void setUp() {
        scene = new JournalCrashScene(dir);
    }

    @AfterEach
    void tearDown() {
        scene.close();
    }

    @Test
    @DisplayName("A change that died with the node leaves the inventory as it was, and the intent is aborted")
    void aChangeThatDiedIsAborted() throws Exception {
        scene.intentOnA();
        // Node A gave the item in memory and stopped; nothing wrote it down.
        long epoch = scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_B, epoch))
                .containsExactly(new InventoryJournalRecovery.Settled(scene.delivery, Settlement.ABORTED));
        assertThat(scene.inventory()).isEqualTo(BEFORE);
        assertThat(scene.deliverAgainOnB(epoch).isSuccess()).isTrue();
        assertThat(scene.inventory()).isEqualTo(AFTER);
    }

    @Test
    @DisplayName("A change a checkpoint wrote down is committed as it stands and never delivered again")
    void aChangeThatWasWrittenIsKept() throws Exception {
        scene.intentOnA();
        scene.write(NODE_A, scene.epochOnA, AFTER);
        long versionAtCrash = scene.version();
        long epoch = scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_B, epoch))
                .containsExactly(new InventoryJournalRecovery.Settled(scene.delivery, Settlement.ROLLED_FORWARD));

        assertThat(scene.journal.loadJournal(scene.delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.COMMITTED);
        assertThat(scene.journal
                        .loadParticipant(scene.delivery, 0)
                        .orElseThrow()
                        .durableApplyState())
                .isEqualTo(ParticipantApplyState.APPLIED);
        assertThat(scene.inventory()).isEqualTo(AFTER);
        assertThat(scene.version()).isEqualTo(versionAtCrash);
        assertThat(scene.deliverAgainOnB(epoch).isSuccess())
                .describedAs("a second delivery of the same operation")
                .isFalse();
        assertThat(scene.inventory()).isEqualTo(AFTER);
    }

    @Test
    @DisplayName("An inventory that matches neither side is quarantined, kept, and nothing is refunded")
    void anUnexplainedInventoryIsQuarantined() throws Exception {
        scene.intentOnA();
        scene.write(NODE_A, scene.epochOnA, "something else entirely");
        long epoch = scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_B, epoch))
                .containsExactly(new InventoryJournalRecovery.Settled(scene.delivery, Settlement.QUARANTINED));

        assertThat(scene.journal.loadJournal(scene.delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.RECOVERY_REQUIRED);
        assertThat(scene.inventory()).isEqualTo("something else entirely");
        scene.journal.purgeSettledBefore(Instant.now().plus(1, ChronoUnit.DAYS));
        assertThat(scene.journal.loadJournal(scene.delivery))
                .describedAs("a quarantined operation is never purged")
                .isPresent();
        assertThat(scene.deliverAgainOnB(epoch).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("A node that lost the player cannot settle what the new node will decide")
    void aStaleNodeCannotSettle() throws Exception {
        scene.intentOnA();
        scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_A, scene.epochOnA))
                .containsExactly(new InventoryJournalRecovery.Settled(scene.delivery, Settlement.REFUSED));
        assertThat(scene.journal.loadJournal(scene.delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.INTENT);
    }

    @Test
    @DisplayName("A node that lost the player cannot commit a change a checkpoint wrote down either")
    void aStaleNodeCannotRollForward() throws Exception {
        scene.intentOnA();
        scene.write(NODE_A, scene.epochOnA, AFTER);
        scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_A, scene.epochOnA))
                .containsExactly(new InventoryJournalRecovery.Settled(scene.delivery, Settlement.REFUSED));
        assertThat(scene.journal.loadJournal(scene.delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.INTENT);
    }
}
