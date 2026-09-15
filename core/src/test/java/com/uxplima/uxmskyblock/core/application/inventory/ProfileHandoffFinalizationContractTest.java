package com.uxplima.uxmskyblock.core.application.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural and contract verification ensuring {@link ProfileHandoffFinalizationPort}
 * represents strictly the dedicated handoff finalization durability path and does NOT purport to be
 * or replace the generic economic {@code InventoryMutationJournal}.
 */
class ProfileHandoffFinalizationContractTest {

    @Test
    @DisplayName("Port naming explicitly represents handoff finalization, not economic journaling")
    void portNamingReflectsHandoffFinalizationBoundary() {
        Class<?> portClass = ProfileHandoffFinalizationPort.class;

        assertThat(portClass.getSimpleName()).contains("HandoffFinalization").doesNotContain("Journal");
    }

    @Test
    @DisplayName("Port contract exposes only finalization flush methods and no generic journal transaction methods")
    void portContractRestrictedToHandoffFinalization() {
        Method[] declaredMethods = ProfileHandoffFinalizationPort.class.getDeclaredMethods();
        List<String> methodNames =
                Arrays.stream(declaredMethods).map(Method::getName).toList();

        // Strictly contains only handoff finalization flush and durable version reader
        assertThat(methodNames).containsExactlyInAnyOrder("finalizeHandoffFlush", "loadLastDurableInventoryVersion");

        // Asserts complete absence of journal protocol methods
        for (String name : methodNames) {
            assertThat(name.toLowerCase(Locale.ROOT))
                    .doesNotContain("journal")
                    .doesNotContain("intent")
                    .doesNotContain("economic")
                    .doesNotContain("trade")
                    .doesNotContain("vault");
        }
    }
}
