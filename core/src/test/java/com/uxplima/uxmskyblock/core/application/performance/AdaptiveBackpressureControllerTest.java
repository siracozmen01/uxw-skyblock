package com.uxplima.uxmskyblock.core.application.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdaptiveBackpressureControllerTest {

    @Test
    @DisplayName("Normal TPS allocates standard block and chunk budgets")
    void normalTpsBudgets() {
        AtomicReference<Double> tpsRef = new AtomicReference<>(20.0);
        AdaptiveBackpressureController controller = new AdaptiveBackpressureController(tpsRef::get);

        assertThat(controller.isThrottlingActive()).isFalse();
        assertThat(controller.resolveBlockPasteBatchSize())
                .isEqualTo(AdaptiveBackpressureController.DEFAULT_NORMAL_BLOCKS_PER_TICK);
        assertThat(controller.resolveChunkDeletionRate())
                .isEqualTo(AdaptiveBackpressureController.DEFAULT_NORMAL_CHUNKS_PER_SEC);
        assertThat(controller.currentTps()).isEqualTo(20.0);
    }

    @Test
    @DisplayName("Sub-19.5 TPS dynamically steps down to throttled budgets")
    void throttledTpsBudgets() {
        AtomicReference<Double> tpsRef = new AtomicReference<>(18.4);
        AdaptiveBackpressureController controller = new AdaptiveBackpressureController(tpsRef::get);

        assertThat(controller.isThrottlingActive()).isTrue();
        assertThat(controller.resolveBlockPasteBatchSize())
                .isEqualTo(AdaptiveBackpressureController.DEFAULT_THROTTLED_BLOCKS_PER_TICK);
        assertThat(controller.resolveChunkDeletionRate())
                .isEqualTo(AdaptiveBackpressureController.DEFAULT_THROTTLED_CHUNKS_PER_SEC);
    }

    @Test
    @DisplayName("Disabling adaptive throttling maintains normal budgets regardless of TPS")
    void disabledThrottlingPreservesNormalBudgets() {
        AtomicReference<Double> tpsRef = new AtomicReference<>(12.0);
        AdaptiveBackpressureController controller = new AdaptiveBackpressureController(
                tpsRef::get,
                false, // disabled
                19.5,
                128,
                16,
                100,
                20);

        assertThat(controller.isThrottlingActive()).isFalse();
        assertThat(controller.resolveBlockPasteBatchSize()).isEqualTo(128);
        assertThat(controller.resolveChunkDeletionRate()).isEqualTo(100);
    }
}
