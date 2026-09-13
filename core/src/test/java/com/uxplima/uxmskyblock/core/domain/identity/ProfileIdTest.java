package com.uxplima.uxmskyblock.core.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProfileIdTest {

    private static final UUID SAMPLE_UUID = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID OTHER_UUID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");

    @Test
    @DisplayName("rejects null UUID in constructor")
    @SuppressWarnings("NullAway")
    void rejectsNullInConstructor() {
        assertThatThrownBy(() -> new ProfileId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null UUID in of() factory")
    @SuppressWarnings("NullAway")
    void rejectsNullInOfFactory() {
        assertThatThrownBy(() -> ProfileId.of(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");
    }

    @Test
    @DisplayName("rejects null or invalid string in fromString() factory")
    @SuppressWarnings("NullAway")
    void rejectsInvalidStringInFromString() {
        assertThatThrownBy(() -> ProfileId.fromString(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value must not be null");

        assertThatThrownBy(() -> ProfileId.fromString("not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("factory methods create equivalent instances with value equality")
    void factoryAndConstructorCreateEqualInstances() {
        ProfileId fromConstructor = new ProfileId(SAMPLE_UUID);
        ProfileId fromOf = ProfileId.of(SAMPLE_UUID);
        ProfileId fromString = ProfileId.fromString("22222222-3333-4444-5555-666666666666");

        assertThat(fromConstructor).isEqualTo(fromOf);
        assertThat(fromConstructor).isEqualTo(fromString);
        assertThat(fromConstructor.hashCode()).isEqualTo(fromOf.hashCode());
        assertThat(fromConstructor.value()).isEqualTo(SAMPLE_UUID);
    }

    @Test
    @DisplayName("distinct UUIDs produce unequal ProfileIds")
    void distinctUuidsAreNotEqual() {
        ProfileId id1 = ProfileId.of(SAMPLE_UUID);
        ProfileId id2 = ProfileId.of(OTHER_UUID);

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    @DisplayName("toString returns canonical UUID string")
    void toStringReturnsCanonicalUuidString() {
        ProfileId id = ProfileId.of(SAMPLE_UUID);
        assertThat(id.toString()).isEqualTo("22222222-3333-4444-5555-666666666666");
    }

    @Test
    @DisplayName("strong typing: ProfileId is not equal to PlayerUuid with same backing UUID")
    void strongTypeSeparationFromPlayerUuid() {
        ProfileId profileId = ProfileId.of(SAMPLE_UUID);
        PlayerUuid playerUuid = PlayerUuid.of(SAMPLE_UUID);

        assertThat(profileId).isNotEqualTo(playerUuid);
        assertThat(profileId).isNotEqualTo(SAMPLE_UUID);
    }
}
