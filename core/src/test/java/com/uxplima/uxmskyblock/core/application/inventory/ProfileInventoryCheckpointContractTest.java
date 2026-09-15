package com.uxplima.uxmskyblock.core.application.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural and contract verification ensuring {@link ProfileInventoryCheckpointPort}
 * represents strictly the ambient CHECKPOINTED durability path and does NOT purport to be
 * or replace the generic economic {@code InventoryMutationJournal}.
 */
class ProfileInventoryCheckpointContractTest {

    @Test
    @DisplayName("Port naming explicitly represents checkpointing, not economic journaling")
    void portNamingReflectsCheckpointBoundary() {
        Class<?> portClass = ProfileInventoryCheckpointPort.class;

        assertThat(portClass.getSimpleName()).contains("Checkpoint").doesNotContain("Journal");
    }

    @Test
    @DisplayName("Port contract exposes only ambient checkpoint methods and no generic journal transaction methods")
    void portContractRestrictedToAmbientCheckpoints() {
        Method[] declaredMethods = ProfileInventoryCheckpointPort.class.getDeclaredMethods();
        List<String> methodNames =
                Arrays.stream(declaredMethods).map(Method::getName).toList();

        // Strictly contains only ambient checkpoint and initialization/load operations
        assertThat(methodNames)
                .containsExactlyInAnyOrder("checkpointInventory", "loadInventory", "initializeInventory");

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
