package com.uxplima.uxmskyblock.core.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventIdTest {

    private static final UUID SAMPLE_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_UUID = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @Test
    @DisplayName("rejects null UUID in constructor")
    @SuppressWarnings("NullAway")
    void rejectsNullInConstructor() {
        assertThatThrownBy(() -> new EventId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null UUID in of() factory")
    @SuppressWarnings("NullAway")
    void rejectsNullInOfFactory() {
        assertThatThrownBy(() -> EventId.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null or invalid string in fromString() factory")
    @SuppressWarnings("NullAway")
    void rejectsInvalidStringInFromString() {
        assertThatThrownBy(() -> EventId.fromString(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");

        assertThatThrownBy(() -> EventId.fromString("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory methods create equivalent instances with value equality")
    void factoryAndConstructorCreateEqualInstances() {
        EventId fromConstructor = new EventId(SAMPLE_UUID);
        EventId fromOf = EventId.of(SAMPLE_UUID);
        EventId fromString = EventId.fromString("11111111-2222-3333-4444-555555555555");

        assertThat(fromConstructor).isEqualTo(fromOf);
        assertThat(fromConstructor).isEqualTo(fromString);
        assertThat(fromConstructor.hashCode()).isEqualTo(fromOf.hashCode());
        assertThat(fromConstructor.value()).isEqualTo(SAMPLE_UUID);
    }

    @Test
    @DisplayName("distinct UUIDs produce unequal EventIds")
    void distinctUuidsAreNotEqual() {
        EventId id1 = EventId.of(SAMPLE_UUID);
        EventId id2 = EventId.of(OTHER_UUID);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("toString returns canonical UUID string")
    void toStringReturnsCanonicalUuidString() {
        EventId id = EventId.of(SAMPLE_UUID);
        assertThat(id.toString()).isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("implements Comparable with UUID unsigned 128-bit binary order")
    void implementsComparableWithUnsigned128BitOrder() {
        UUID uuidLow = new UUID(0x7FFFFFFFFFFFFFFFL, 0L);
        UUID uuidHigh = new UUID(0x8000000000000000L, 0L);

        EventId idLow = EventId.of(uuidLow);
        EventId idHigh = EventId.of(uuidHigh);

        assertThat(idLow.compareTo(idHigh)).isLessThan(0);
        assertThat(idHigh.compareTo(idLow)).isGreaterThan(0);

        UUID uuidLsbLow = new UUID(0x1000L, 0x7FFFFFFFFFFFFFFFL);
        UUID uuidLsbHigh = new UUID(0x1000L, 0x8000000000000000L);

        EventId idLsbLow = EventId.of(uuidLsbLow);
        EventId idLsbHigh = EventId.of(uuidLsbHigh);

        assertThat(idLsbLow.compareTo(idLsbHigh)).isLessThan(0);
        assertThat(idLsbHigh.compareTo(idLsbLow)).isGreaterThan(0);
        assertThat(idLow.compareTo(EventId.of(uuidLow))).isZero();
    }
}
