package com.uxplima.uxmskyblock.persistence.inventory;

import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.ADA_AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.ADA_BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.BO_AFTER;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.BO_BEFORE;
import static com.uxplima.uxmskyblock.persistence.inventory.TradeCrashScene.NODE_B;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery.Settled;
import com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery.Settlement;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.ParticipantApplyState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A trade cut short by a crash is settled side by side, each side under its own player's session, by
 * what reached durable storage.
 *
 * <p>The testing standard's {@code CrossRegionTradeCrashRecoveryTest} and the game mode spec's
 * {@code CrossOwnerInventoryCrashRecoveryTest} are this matrix: a side that holds its half while the other
 * holds nothing is put back from the inventory the intent kept, a trade both sides hold is committed,
 * a trade neither holds is aborted, and a side holding anything else quarantines the trade with nothing
 * given or taken.
 */
class ATradeCutShortIsSettledSideBySideTest {

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
    @DisplayName("One side changed and kept, the other never reached: the side is put back and nothing is given twice")
    void oneSideIsPutBack() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        // Ada's half landed and was recorded, then she left with it; node A stopped before Bo's half.
        assertThat(scene.journal.markApplied(scene.trade, 0).isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);
        long adaVersionAtCrash = scene.ada.version();
        long boVersionAtCrash = scene.bo.version();
        scene.ada.comesToNodeB();

        assertThat(scene.ada.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.ABORTED));

        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
        assertThat(scene.ada.version()).isEqualTo(adaVersionAtCrash + 1);
        assertThat(scene.bo.inventory()).isEqualTo(BO_BEFORE);
        assertThat(scene.bo.version()).isEqualTo(boVersionAtCrash);
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.ABORTED);
        assertThat(scene.journal.participants(scene.trade))
                .allSatisfy(side -> assertThat(side.applyState()).isEqualTo(ParticipantApplyState.REVERTED));

        scene.bo.comesToNodeB();
        assertThat(scene.bo.recoversOnNodeB()).isEmpty();
        assertThat(scene.bo.inventory()).isEqualTo(BO_BEFORE);
    }

    @Test
    @DisplayName("The side that holds nothing comes first: it is released, plays on, and the other is put back later")
    void theEmptySideComesFirst() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);
        scene.bo.comesToNodeB();

        assertThat(scene.bo.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.RELEASED));
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);
        assertThat(scene.bo.checkpoint(NODE_B, "diamond x1, stick x2").isSuccess())
                .describedAs("a side put back is the player's again while the trade waits for the other")
                .isTrue();

        scene.ada.comesToNodeB();
        assertThat(scene.ada.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.ABORTED));
        assertThat(scene.ada.inventory()).isEqualTo(ADA_BEFORE);
        assertThat(scene.bo.inventory()).isEqualTo("diamond x1, stick x2");
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.ABORTED);
    }

    @Test
    @DisplayName("Both sides left with the trade: it is committed as it stands, from whichever side comes first")
    void bothSidesHoldIt() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith(ADA_AFTER);
        scene.bo.leavesWith(BO_AFTER);
        scene.bo.comesToNodeB();

        assertThat(scene.bo.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.ROLLED_FORWARD));
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.COMMITTED);
        assertThat(scene.ada.inventory()).isEqualTo(ADA_AFTER);
        assertThat(scene.bo.inventory()).isEqualTo(BO_AFTER);
    }

    @Test
    @DisplayName("The trade died with the node: it is aborted and no inventory is written")
    void neitherSideHoldsIt() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        long adaVersion = scene.ada.version();
        long boVersion = scene.bo.version();
        scene.ada.comesToNodeB();

        assertThat(scene.ada.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.ABORTED));
        assertThat(scene.ada.version()).isEqualTo(adaVersion);
        assertThat(scene.bo.version()).isEqualTo(boVersion);
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.ABORTED);
    }

    @Test
    @DisplayName("A side holding something the trade cannot explain quarantines it, and nothing is given or taken")
    void driftIsQuarantined() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.leavesWith("bread x1, diamond x1, emerald x1");
        scene.ada.comesToNodeB();

        assertThat(scene.ada.recoversOnNodeB()).containsExactly(new Settled(scene.trade, Settlement.QUARANTINED));
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.RECOVERY_REQUIRED);
        assertThat(scene.ada.inventory()).isEqualTo("bread x1, diamond x1, emerald x1");
        assertThat(scene.bo.inventory()).isEqualTo(BO_BEFORE);
    }

    @Test
    @DisplayName("The recovery of a single player's operations leaves a trade to the trade's own")
    void singleRecoveryLeavesTradesAlone() throws Exception {
        assertThat(scene.intentOnA().isSuccess()).isTrue();
        scene.ada.comesToNodeB();

        InventoryJournalRecovery single = new InventoryJournalRecovery(
                new PlayerInventoryMutationJournalAdapter(scene.database), scene.inventories);
        assertThat(single.recover(scene.ada.player, scene.ada.profile, NODE_B, scene.ada.epoch))
                .isEmpty();
        assertThat(scene.journal.state(scene.trade)).hasValue(InventoryMutationJournalState.INTENT);
    }
}
