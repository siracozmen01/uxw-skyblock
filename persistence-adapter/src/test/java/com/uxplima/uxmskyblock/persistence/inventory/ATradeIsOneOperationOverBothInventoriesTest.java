package com.uxplima.uxmskyblock.persistence.inventory;

import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.ADA_AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.ADA_BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.BO_AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.BO_BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.NODE_A;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A trade is one journaled operation over both players' inventories, never two mutations committed one
 * after the other: one operation id, both sessions checked, both versions moved in one transaction or
 * neither.
 *
 * <p>This is the game mode spec's {@code CrossOwnerEconomicInventoryTransferContractTest} for two players.
 */
class ATradeIsOneOperationOverBothInventoriesTest {

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
    @DisplayName("One operation goes from INTENT to COMMITTED, its intent and its commit each moving both versions")
    void oneOperationMovesBoth() throws Exception {
        long ada = scene.ada.version();
        long bo = scene.bo.version();

        assertThat(scene.intentOnA().isSuccess()).isTrue();
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);
        assertThat(scene.journal.participants(scene.trade)).hasSize(2);
        assertThat(scene.journal.findOpenTrades(scene.ada.profile)).containsExactly(scene.trade);
        assertThat(scene.journal.findOpenTrades(scene.bo.profile)).containsExactly(scene.trade);

        assertThat(scene.journal
                        .commit(scene.trade, NODE_A, List.of(scene.ada.outcome(), scene.bo.outcome()))
                        .isSuccess())
                .isTrue();

        assertThat(scene.journals()).isEqualTo(1);
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.COMMITTED);
        assertThat(scene.journal.participants(scene.trade))
                .allSatisfy(side -> assertThat(side.applyState()).isEqualTo(ParticipantApplyState.APPLIED));
        assertThat(scene.ada.version()).isEqualTo(ada + 2);
        assertThat(scene.bo.version()).isEqualTo(bo + 2);
        assertThat(scene.ada.inventory()).isEqualTo(ADA_AFTER);
        assertThat(scene.bo.inventory()).isEqualTo(BO_AFTER);
        assertThat(scene.journal.findOpenTrades(scene.ada.profile)).isEmpty();
    }

    @Test
    @DisplayName("A session that moved refuses the whole commit, and neither inventory is written")
    void aMovedSessionRefusesBoth() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        long ada = scene.ada.version();
        scene.bo.epoch++;

        assertThat(scene.journal
                        .commit(scene.trade, NODE_A, List.of(scene.ada.outcome(), scene.bo.outcome()))
                        .rejectionReason())
                .hasValue("STALE_EPOCH");
        assertThat(scene.ada.version()).isEqualTo(ada);
        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
        assertThat(scene.bo.inventory()).isEqualTo(BO_BEFORE);
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);
    }

    @Test
    @DisplayName("A side whose inventory moved before the intent refuses it, and no journal is left behind")
    void aMovedVersionRefusesTheIntent() throws Exception {
        TradeJournalSides sides = new TradeJournalSides(scene);
        assertThat(scene.bo.checkpoint(NODE_A, "diamond x1, dirt x1").isSuccess())
                .isTrue();

        assertThat(sides.recordWithStaleBo().rejectionReason()).hasValue("OCC_VERSION_MISMATCH");
        assertThat(scene.journals()).isZero();
    }

    @Test
    @DisplayName("No checkpoint writes either side while the trade is open, and both are free once it ends")
    void anOpenTradeHoldsBothInventories() {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        assertThat(scene.ada.checkpoint(NODE_A, "anything").isSuccess()).isFalse();
        assertThat(scene.bo.checkpoint(NODE_A, "anything").isSuccess()).isFalse();

        assertThat(scene.journal
                        .abort(scene.trade, NODE_A, List.of(scene.ada.holder(), scene.bo.holder()))
                        .isSuccess())
                .isTrue();
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.ABORTED);
        assertThat(scene.ada.checkpoint(NODE_A, ADA_BEFORE).isSuccess()).isTrue();
        assertThat(scene.bo.checkpoint(NODE_A, BO_BEFORE).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("The same operation id cannot be recorded twice")
    void anOperationIsRecordedOnce() {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        assertThat(scene.intentOnA().isConflict()).isTrue();
    }

    /** Records the intent with the versions as they were before Bo's inventory moved. */
    private static final class TradeJournalSides {
        private final TradeCrashScene scene;
        private final com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort.Side ada;
        private final com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort.Side bo;

        TradeJournalSides(TradeCrashScene scene) {
            this.scene = scene;
            this.ada = scene.ada.side();
            this.bo = scene.bo.side();
        }

        com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome recordWithStaleBo() {
            return scene.journal.recordIntent(
                    scene.trade, NODE_A, List.of(ada, bo), "{}", java.time.Duration.ofMinutes(1));
        }
    }
}
