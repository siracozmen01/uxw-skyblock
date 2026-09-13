package com.uxplima.uxmskyblock.core.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerUuidTest {

    private static final UUID SAMPLE_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    @DisplayName("rejects null UUID in constructor")
    @SuppressWarnings("NullAway")
    void rejectsNullInConstructor() {
        assertThatThrownBy(() -> new PlayerUuid(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null UUID in of() factory")
    @SuppressWarnings("NullAway")
    void rejectsNullInOfFactory() {
        assertThatThrownBy(() -> PlayerUuid.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null or invalid string in fromString() factory")
    @SuppressWarnings("NullAway")
    void rejectsInvalidStringInFromString() {
        assertThatThrownBy(() -> PlayerUuid.fromString(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");

        assertThatThrownBy(() -> PlayerUuid.fromString("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory methods create equivalent instances with value equality")
    void factoryAndConstructorCreateEqualInstances() {
        PlayerUuid fromConstructor = new PlayerUuid(SAMPLE_UUID);
        PlayerUuid fromOf = PlayerUuid.of(SAMPLE_UUID);
        PlayerUuid fromString = PlayerUuid.fromString("11111111-2222-3333-4444-555555555555");

        assertThat(fromConstructor).isEqualTo(fromOf);
        assertThat(fromConstructor).isEqualTo(fromString);
        assertThat(fromConstructor.hashCode()).isEqualTo(fromOf.hashCode());
        assertThat(fromConstructor.value()).isEqualTo(SAMPLE_UUID);
    }

    @Test
    @DisplayName("distinct UUIDs produce unequal PlayerUuids")
    void distinctUuidsAreNotEqual() {
        PlayerUuid id1 = PlayerUuid.of(SAMPLE_UUID);
        PlayerUuid id2 = PlayerUuid.of(OTHER_UUID);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("toString returns canonical UUID string")
    void toStringReturnsCanonicalUuidString() {
        PlayerUuid id = PlayerUuid.of(SAMPLE_UUID);
        assertThat(id.toString()).isEqualTo("11111111-2222-3333-4444-555555555555");
    }

    @Test
    @DisplayName("implements Comparable with UUID unsigned 128-bit binary order")
    void implementsComparableWithUnsigned128BitOrder() {
        // Test MSB comparison where signed order != unsigned order:
        // msbPositive (0x7FFFFFFFFFFFFFFFL) has signed value > 0, unsigned value 0x7FFF...
        // msbHighBit  (0x8000000000000000L) has signed value < 0, but unsigned value > 0x7FFF...
        UUID uuidLow = new UUID(0x7FFFFFFFFFFFFFFFL, 0L);
        UUID uuidHigh = new UUID(0x8000000000000000L, 0L);

        PlayerUuid idLow = PlayerUuid.of(uuidLow);
        PlayerUuid idHigh = PlayerUuid.of(uuidHigh);

        assertThat(idLow.compareTo(idHigh))
                .as("Unsigned order mandates 0x7FFF... < 0x8000... even though signed compare is opposite")
                .isLessThan(0);
        assertThat(idHigh.compareTo(idLow)).isGreaterThan(0);

        // Test LSB comparison when MSBs are equal:
        UUID uuidLsbLow = new UUID(0x1000L, 0x7FFFFFFFFFFFFFFFL);
        UUID uuidLsbHigh = new UUID(0x1000L, 0x8000000000000000L);

        PlayerUuid idLsbLow = PlayerUuid.of(uuidLsbLow);
        PlayerUuid idLsbHigh = PlayerUuid.of(uuidLsbHigh);

        assertThat(idLsbLow.compareTo(idLsbHigh)).isLessThan(0);
        assertThat(idLsbHigh.compareTo(idLsbLow)).isGreaterThan(0);

        // Reflexive / equality case
        PlayerUuid idLowCopy = PlayerUuid.of(uuidLow);
        assertThat(idLow.compareTo(idLowCopy)).isZero();
    }
}
