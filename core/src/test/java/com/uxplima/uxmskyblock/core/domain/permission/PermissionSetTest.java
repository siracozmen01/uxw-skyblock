package com.uxplima.uxmskyblock.core.domain.permission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PermissionSetTest {

    @Test
    @DisplayName("PermissionSet operations: bit tests, immutability, union, and intersection")
    void bitsetOperations() {
        PermissionId id0 = PermissionId.of(0);
        PermissionId id5 = PermissionId.of(5);
        PermissionId id12 = PermissionId.of(12);

        PermissionSet set1 = PermissionSet.of(id0, id5);
        assertThat(set1.has(id0)).isTrue();
        assertThat(set1.has(id5)).isTrue();
        assertThat(set1.has(id12)).isFalse();
        assertThat(set1.cardinality()).isEqualTo(2);

        // Immutability on with/without
        PermissionSet set2 = set1.with(id12);
        assertThat(set1.has(id12)).isFalse();
        assertThat(set2.has(id12)).isTrue();
        assertThat(set2.cardinality()).isEqualTo(3);

        PermissionSet set3 = set2.without(id5);
        assertThat(set3.has(id5)).isFalse();
        assertThat(set3.has(id0)).isTrue();
        assertThat(set3.has(id12)).isTrue();
        assertThat(set3.cardinality()).isEqualTo(2);

        // Union & Intersect
        PermissionSet union = set1.union(PermissionSet.of(id12));
        assertThat(union).isEqualTo(set2);

        PermissionSet intersect = set1.intersect(set2);
        assertThat(intersect).isEqualTo(set1);
    }
}
