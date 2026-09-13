package com.uxplima.uxmskyblock.core.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandIdTest {

    private static final UUID SAMPLE_UUID = UUID.fromString("33333333-4444-5555-6666-777777777777");
    private static final UUID OTHER_UUID = UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000");

    @Test
    @DisplayName("rejects null UUID in constructor")
    @SuppressWarnings("NullAway")
    void rejectsNullInConstructor() {
        assertThatThrownBy(() -> new IslandId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null UUID in of() factory")
    @SuppressWarnings("NullAway")
    void rejectsNullInOfFactory() {
        assertThatThrownBy(() -> IslandId.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null or invalid string in fromString() factory")
    @SuppressWarnings("NullAway")
    void rejectsInvalidStringInFromString() {
        assertThatThrownBy(() -> IslandId.fromString(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");

        assertThatThrownBy(() -> IslandId.fromString("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory methods create equivalent instances with value equality")
    void factoryAndConstructorCreateEqualInstances() {
        IslandId fromConstructor = new IslandId(SAMPLE_UUID);
        IslandId fromOf = IslandId.of(SAMPLE_UUID);
        IslandId fromString = IslandId.fromString("33333333-4444-5555-6666-777777777777");

        assertThat(fromConstructor).isEqualTo(fromOf);
        assertThat(fromConstructor).isEqualTo(fromString);
        assertThat(fromConstructor.hashCode()).isEqualTo(fromOf.hashCode());
        assertThat(fromConstructor.value()).isEqualTo(SAMPLE_UUID);
    }

    @Test
    @DisplayName("distinct UUIDs produce unequal IslandIds")
    void distinctUuidsAreNotEqual() {
        IslandId id1 = IslandId.of(SAMPLE_UUID);
        IslandId id2 = IslandId.of(OTHER_UUID);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("toString returns canonical UUID string")
    void toStringReturnsCanonicalUuidString() {
        IslandId id = IslandId.of(SAMPLE_UUID);
        assertThat(id.toString()).isEqualTo("33333333-4444-5555-6666-777777777777");
    }

    @Test
    @DisplayName("implements Comparable with UUID unsigned 128-bit binary order")
    void implementsComparableWithUnsigned128BitOrder() {
        // Test MSB comparison where signed order != unsigned order:
        // msbPositive (0x7FFFFFFFFFFFFFFFL) has signed value > 0, unsigned value 0x7FFF...
        // msbHighBit  (0x8000000000000000L) has signed value < 0, but unsigned value > 0x7FFF...
        UUID uuidLow = new UUID(0x7FFFFFFFFFFFFFFFL, 0L);
        UUID uuidHigh = new UUID(0x8000000000000000L, 0L);

        IslandId idLow = IslandId.of(uuidLow);
        IslandId idHigh = IslandId.of(uuidHigh);

        assertThat(idLow.compareTo(idHigh))
                .as("Unsigned order mandates 0x7FFF... < 0x8000... even though signed compare is opposite")
                .isLessThan(0);
        assertThat(idHigh.compareTo(idLow)).isGreaterThan(0);

        // Test LSB comparison when MSBs are equal:
        UUID uuidLsbLow = new UUID(0x1000L, 0x7FFFFFFFFFFFFFFFL);
        UUID uuidLsbHigh = new UUID(0x1000L, 0x8000000000000000L);

        IslandId idLsbLow = IslandId.of(uuidLsbLow);
        IslandId idLsbHigh = IslandId.of(uuidLsbHigh);

        assertThat(idLsbLow.compareTo(idLsbHigh)).isLessThan(0);
        assertThat(idLsbHigh.compareTo(idLsbLow)).isGreaterThan(0);

        // Reflexive comparison
        IslandId idLowCopy = IslandId.of(uuidLow);
        assertThat(idLow.compareTo(idLowCopy)).isZero();
    }

    @Test
    @DisplayName("strong typing: IslandId is not equal to ProfileId or PlayerUuid with same backing UUID")
    void strongTypeSeparation() {
        IslandId islandId = IslandId.of(SAMPLE_UUID);
        ProfileId profileId = ProfileId.of(SAMPLE_UUID);
        PlayerUuid playerUuid = PlayerUuid.of(SAMPLE_UUID);

        assertThat(islandId).isNotEqualTo(profileId);
        assertThat(islandId).isNotEqualTo(playerUuid);
        assertThat(islandId).isNotEqualTo(SAMPLE_UUID);
    }
}
