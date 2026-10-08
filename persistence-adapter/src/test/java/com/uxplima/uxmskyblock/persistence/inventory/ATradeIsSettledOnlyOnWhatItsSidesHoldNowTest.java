package com.uxplima.uxmskyblock.persistence.inventory;

import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.ADA_AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.ADA_BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.BO_AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.NODE_A;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.NODE_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery.Settled;
import com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery.Settlement;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery decides from what it read, and the journal acts on the decision only while it still holds.
 *
 * <p>Two players' recoveries can run at once on two nodes, each from a picture the other is about to
 * change. Every settlement is checked again inside its own transaction, under the locks, against what
 * the inventories hold then: a trade is committed only while every side holds its outcome, a side is
 * put back only while it holds its half, and a trade is closed only while no other side holds its half.
 */
class ATradeIsSettledOnlyOnWhatItsSidesHoldNowTest {

    @TempDir
    Path dir;

    private TradeCrashScene scene;

    @BeforeEach
    void setUp() {
        scene = new TradeCrashScene(dir);
    }

    @AfterEach
    void tearDown() {
        scene.close();
    }

    @Test
    @DisplayName("A trade a side was put back from is not committed after it, by a recovery or by its node")
    void aPutBackSideBlocksTheCommit() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);
        scene.bo.comesToNodeB();
        assertThat(scene.bo.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.RELEASED));
        // Bo's side was released while Ada's recovery read both sides as holding the trade.
        scene.ada.comesToNodeB();

        assertThat(scene.journal
                        .settleTrade(scene.trade, NODE_B, scene.ada.holder(), InventoryMutationJournalState.COMMITTED)
                        .rejectionReason())
                .hasValue("NOT_EVERY_SIDE_HOLDS_IT");
        assertThat(scene.journal
                        .commit(scene.trade, NODE_A, List.of(scene.ada.outcome(), scene.bo.outcome()))
                        .rejectionReason())
                .hasValue("SIDE_PUT_BACK");
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);
    }

    @Test
    @DisplayName("A trade is not committed while a side does not hold its outcome")
    void aSideWithoutItsOutcomeBlocksTheCommit() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);
        scene.ada.comesToNodeB();

        assertThat(scene.journal
                        .settleTrade(scene.trade, NODE_B, scene.ada.holder(), InventoryMutationJournalState.COMMITTED)
                        .rejectionReason())
                .hasValue("NOT_EVERY_SIDE_HOLDS_IT");
    }

    @Test
    @DisplayName("A side is put back only while it holds its half, and only once")
    void aSideIsPutBackOnlyFromItsHalf() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.comesToNodeB();
        byte[] kept = scene.journal.participants(scene.trade).get(0).beforeInventory();

        assertThat(scene.journal
                        .settleSide(scene.trade, 0, NODE_B, scene.ada.holder(), kept, scene.ada.version(), false)
                        .rejectionReason())
                .describedAs("Ada holds what the trade found, not its half")
                .hasValue("SIDE_MOVED");
        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
    }

    @Test
    @DisplayName("A trade is not closed while another side holds its half")
    void anotherHalfBlocksTheClose() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);
        scene.bo.comesToNodeB();

        assertThat(scene.journal
                        .settleSide(scene.trade, 1, NODE_B, scene.bo.holder(), null, scene.bo.version(), true)
                        .rejectionReason())
                .hasValue("ANOTHER_SIDE_HOLDS_ITS_HALF");
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);
    }

    @Test
    @DisplayName("A side released while it holds something else is refused")
    void aSideIsReleasedOnlyFromWhatItHad() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.bo.leavesWith(BO_AFTER + ", stick x1");
        scene.bo.comesToNodeB();

        assertThat(scene.journal
                        .settleSide(scene.trade, 1, NODE_B, scene.bo.holder(), null, scene.bo.version(), false)
                        .rejectionReason())
                .hasValue("SIDE_MOVED");
    }

    @Test
    @DisplayName("The intent writes each side down as the trade found it, at the version it has")
    void theIntentWritesTheSidesAsTheyStand() {
        assertThat(scene.ada.checkpoint(NODE_A, "a minute old").isSuccess()).isTrue();
        long version = scene.ada.version();

        assertThat(scene.intentOnA().isSuccess()).isTrue();

        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
        assertThat(scene.ada.version()).isEqualTo(version + 1);
    }

    @Test
    @DisplayName("A player who leaves mid-trade cannot write their half over it: only the trade writes its sides")
    void aLastWriteMidTradeIsRefused() {
        long known = scene.ada.version();
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        assertThat(scene.sessions
                        .drain(scene.ada.player, NODE_A, scene.ada.epoch)
                        .isSuccess())
                .isTrue();

        assertThat(new PlayerProfileHandoffFinalizationAdapter(scene.database)
                        .finalizeHandoffFlush(
                                scene.ada.player,
                                scene.ada.profile,
                                NODE_A,
                                scene.ada.epoch,
                                known,
                                com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord.createDefault(
                                        scene.ada.profile, TradeCrashScene.bytes(ADA_AFTER), new byte[0]))
                        .isSuccess())
                .isFalse();
        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
    }

    @Test
    @DisplayName("A trade is not aborted over a side that left holding its half: recovery puts it back")
    void anAbortLeavesAWrittenSideToRecovery() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);

        assertThat(scene.journal
                        .abort(scene.trade, NODE_A, List.of(scene.ada.holder(), scene.bo.holder()))
                        .rejectionReason())
                .hasValue("SIDE_WRITTEN");
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);

        scene.ada.comesToNodeB();
        assertThat(scene.ada.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.ABORTED));
        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
    }
}
