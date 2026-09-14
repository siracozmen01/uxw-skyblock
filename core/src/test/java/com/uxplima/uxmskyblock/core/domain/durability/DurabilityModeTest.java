package com.uxplima.uxmskyblock.core.domain.durability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DurabilityModeTest {

    @Test
    @DisplayName("DurabilityMode defines canonical HYBRID product mode")
    void definesCanonicalHybridMode() {
        assertThat(DurabilityMode.valueOf("HYBRID")).isEqualTo(DurabilityMode.HYBRID);
        assertThat(DurabilityMode.values()).containsExactly(DurabilityMode.HYBRID);
    }
}
