package com.uxplima.uxmskyblock.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NamespacedIdTest {

    @Test
    @DisplayName("rejects null input in single-arg factory")
    @SuppressWarnings("NullAway")
    void rejectsNullInput() {
        assertThatThrownBy(() -> NamespacedId.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("namespacedString");
    }

    @Test
    @DisplayName("creates instance and returns logical identifier via asString()")
    void createsInstanceAndReturnsAsString() {
        NamespacedId id1 = NamespacedId.of("uxm:trade_winds");
        assertThat(id1.asString()).isEqualTo("uxm:trade_winds");

        NamespacedId id2 = NamespacedId.of("brix:creative");
        assertThat(id2.asString()).isEqualTo("brix:creative");

        NamespacedId id3 = NamespacedId.of("factory:conveyor_plot");
        assertThat(id3.asString()).isEqualTo("factory:conveyor_plot");
    }

    @Test
    @DisplayName("satisfies value equality and hashCode contract")
    void satisfiesValueEqualityAndHashCode() {
        NamespacedId a1 = NamespacedId.of("uxm:island");
        NamespacedId a2 = NamespacedId.of("uxm:island");
        NamespacedId b = NamespacedId.of("uxm:vessel");

        assertThat(a1).isEqualTo(a2);
        assertThat(a1.hashCode()).isEqualTo(a2.hashCode());
        assertThat(a1).isNotEqualTo(b);
        assertThat(a1.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    @DisplayName("NamespacedId does not implement Comparable (ordering belongs to protocol comparators)")
    void doesNotImplementComparable() {
        assertThat(Comparable.class.isAssignableFrom(NamespacedId.class)).isFalse();
    }
}
