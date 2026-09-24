package com.uxplima.uxmskyblock.persistence.inventory;

import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.NODE_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

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
 * A crash after the intent was written and before the inventory was touched ends in a clean abort.
 *
 * <p>The testing standard names this test. Node A writes the intent and stops. When node B takes the
 * player, recovery finds the inventory exactly as the intent found it: the journal is aborted, the
 * participant reverted, nothing is refunded and the inventory is left as it was. The delivery can
 * then be made again, and it lands once.
 */
class InventoryJournalCrashBeforeApplyTest {

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
    @DisplayName("An intent cut short before the inventory changed is aborted, and nothing is refunded")
    void anIntentCutShortIsAborted() throws Exception {
        scene.intentOnA();
        long versionAtCrash = scene.version();
        long epoch = scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_B, epoch))
                .containsExactly(new InventoryJournalRecovery.Settled(scene.delivery, Settlement.ABORTED));

        assertThat(scene.journal.loadJournal(scene.delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.ABORTED);
        assertThat(scene.journal
                        .loadParticipant(scene.delivery, 0)
                        .orElseThrow()
                        .durableApplyState())
                .isEqualTo(ParticipantApplyState.REVERTED);
        assertThat(scene.inventory()).describedAs("nothing refunded").isEqualTo(BEFORE);
        assertThat(scene.version()).isEqualTo(versionAtCrash);
        assertThat(scene.journal.findOpenIntents(scene.profile)).isEmpty();

        assertThat(scene.deliverAgainOnB(epoch).isSuccess())
                .describedAs("the delivery, made again")
                .isTrue();
        assertThat(scene.inventory()).isEqualTo(AFTER);
        assertThat(scene.version()).isEqualTo(versionAtCrash + 1);
    }

    @Test
    @DisplayName("Recovery has nothing to do when no intent was left open")
    void nothingOpenNothingDone() throws Exception {
        long epoch = scene.nodeBTakesOver();

        assertThat(scene.recoverOn(NODE_B, epoch)).isEmpty();
        assertThat(scene.inventory()).isEqualTo(BEFORE);
    }
}
