package com.uxplima.uxmskyblock.core.domain.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RestoreModeTest {

    @Test
    @DisplayName("Geometry only writes no relational row at all")
    void geometryOnlyTouchesNoTable() {
        assertThat(RestoreMode.GEOMETRY_ONLY.restoresRelationalState()).isFalse();
        assertThat(RestoreMode.GEOMETRY_ONLY.restoresMembership()).isFalse();
    }

    @Test
    @DisplayName("The world content safe mode restores the island but leaves its membership alone")
    void worldContentSafeLeavesMembership() {
        assertThat(RestoreMode.WORLD_CONTENT_SAFE.restoresRelationalState()).isTrue();
        assertThat(RestoreMode.WORLD_CONTENT_SAFE.restoresMembership()).isFalse();
    }

    @Test
    @DisplayName("Only the full island mode puts membership back")
    void onlyFullIslandRestoresMembership() {
        assertThat(RestoreMode.FULL_ISLAND.restoresRelationalState()).isTrue();
        assertThat(RestoreMode.FULL_ISLAND.restoresMembership()).isTrue();
    }

    @Test
    @DisplayName("A caller who names no mode gets the one that changes least")
    void theDefaultIsTheSafeOne() {
        assertThat(RestoreMode.safeDefault()).isEqualTo(RestoreMode.WORLD_CONTENT_SAFE);
    }
}
