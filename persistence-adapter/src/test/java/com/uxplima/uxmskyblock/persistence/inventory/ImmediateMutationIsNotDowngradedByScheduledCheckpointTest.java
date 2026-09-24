package com.uxplima.uxmskyblock.persistence.inventory;

import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.NODE_A;
import static com.uxplima.uxmskyblock.persistence.inventory.JournalCrashScene.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmskyblock.core.application.inventory.JournaledInventoryMutationService;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryFingerprint;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A journaled mutation keeps its own durability; an ambient checkpoint cannot write under it.
 *
 * <p>The testing standard names this test. An item reward goes through the journal: intent, the item
 * in memory, commit. A checkpoint that fell in the middle wrote the inventory with the item in it
 * and moved the version, so the commit was refused, the item taken back in memory and the intent
 * aborted, while the durable inventory kept the item. A crash before the next checkpoint gave the
 * player the item and left the reward to be delivered again. The checkpoint now waits while the
 * journal holds the inventory, and the mutation commits on its own protocol.
 */
class ImmediateMutationIsNotDowngradedByScheduledCheckpointTest {

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
    @DisplayName("A checkpoint in the middle of a delivery waits, and the delivery commits on its own")
    void aCheckpointInTheMiddleWaits() {
        long version = scene.version();
        AtomicReference<ProfileInventoryMutationOutcome> checkpoint = new AtomicReference<>();
        AtomicBoolean undone = new AtomicBoolean();

        Result<JournaledInventoryMutationService.MutationSuccess<Boolean>, String> delivered =
                new JournaledInventoryMutationService(scene.journal)
                        .execute(
                                scene.player,
                                scene.profile,
                                NODE_A,
                                scene.epochOnA,
                                version,
                                scene.delivery,
                                "REWARD_DELIVERY",
                                InventoryFingerprint.of(bytes(BEFORE)),
                                InventoryFingerprint.of(bytes(AFTER)),
                                "{}",
                                Duration.ofMinutes(1),
                                () -> {
                                    // The item is in memory, and the ambient checkpoint fires now.
                                    checkpoint.set(scene.inventories.checkpointInventory(
                                            scene.player,
                                            scene.profile,
                                            NODE_A,
                                            scene.epochOnA,
                                            version,
                                            ProfileInventoryRecord.createDefault(
                                                    scene.profile, bytes(AFTER), new byte[0])));
                                    return Result.ok(new JournaledInventoryMutationService.MutationExecution<>(
                                            true, bytes(AFTER)));
                                },
                                () -> undone.set(true));

        assertThat(java.util.Objects.requireNonNull(checkpoint.get()).isSuccess())
                .describedAs("the checkpoint while the journal holds the inventory")
                .isFalse();
        assertThat(delivered.isOk()).describedAs("the delivery").isTrue();
        assertThat(undone).describedAs("the item was never taken back").isFalse();
        assertThat(scene.journal.loadJournal(scene.delivery).orElseThrow().state())
                .isEqualTo(InventoryMutationJournalState.COMMITTED);
        assertThat(scene.inventory()).isEqualTo(AFTER);
        assertThat(scene.version()).isEqualTo(version + 1);
    }

    @Test
    @DisplayName("Once the intent settles the checkpoint writes again")
    void theCheckpointWritesOnceTheIntentSettles() {
        scene.intentOnA();
        assertThat(scene.inventories
                        .checkpointInventory(
                                scene.player,
                                scene.profile,
                                NODE_A,
                                scene.epochOnA,
                                scene.version(),
                                ProfileInventoryRecord.createDefault(scene.profile, bytes("moved"), new byte[0]))
                        .isSuccess())
                .isFalse();
        assertThat(scene.inventory()).isEqualTo(BEFORE);

        assertThat(scene.journal
                        .abortIntent(scene.player, scene.profile, NODE_A, scene.epochOnA, scene.delivery)
                        .isSuccess())
                .isTrue();

        scene.write(NODE_A, scene.epochOnA, "moved");
        assertThat(scene.inventory()).isEqualTo("moved");
    }
}
