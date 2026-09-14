package com.uxplima.uxmskyblock.core.domain.durability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DurabilityClassificationTest {

    @Test
    @DisplayName("DurabilityClassification defines IMMEDIATE and CHECKPOINTED mutation categories")
    void definesMutationCategories() {
        assertThat(DurabilityClassification.valueOf("IMMEDIATE")).isEqualTo(DurabilityClassification.IMMEDIATE);
        assertThat(DurabilityClassification.valueOf("CHECKPOINTED")).isEqualTo(DurabilityClassification.CHECKPOINTED);
        assertThat(DurabilityClassification.values())
                .containsExactlyInAnyOrder(DurabilityClassification.IMMEDIATE, DurabilityClassification.CHECKPOINTED);
    }
}
