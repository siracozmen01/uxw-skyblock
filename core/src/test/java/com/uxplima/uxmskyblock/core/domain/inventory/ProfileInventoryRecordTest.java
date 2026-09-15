package com.uxplima.uxmskyblock.core.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("NullAway")
class ProfileInventoryRecordTest {

    private static final ProfileId PROFILE_ID = ProfileId.of(UUID.randomUUID());
    private static final byte[] INV_BYTES = new byte[] {1, 2, 3};
    private static final byte[] EC_BYTES = new byte[] {4, 5, 6};

    @Test
    @DisplayName("rejects null profileId")
    void rejectsNullProfileId() {
        assertThatThrownBy(() -> new ProfileInventoryRecord(
                        null,
                        1L,
                        INV_BYTES,
                        EC_BYTES,
                        0,
                        20.0,
                        20,
                        5.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SURVIVAL",
                        false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("profileId");
    }

    @Test
    @DisplayName("rejects non-positive version")
    void rejectsNonPositiveVersion() {
        assertThatThrownBy(() -> new ProfileInventoryRecord(
                        PROFILE_ID,
                        0L,
                        INV_BYTES,
                        EC_BYTES,
                        0,
                        20.0,
                        20,
                        5.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SURVIVAL",
                        false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version must be positive");
    }

    @Test
    @DisplayName("rejects null inventoryNbt")
    void rejectsNullInventoryNbt() {
        assertThatThrownBy(() -> new ProfileInventoryRecord(
                        PROFILE_ID,
                        1L,
                        null,
                        EC_BYTES,
                        0,
                        20.0,
                        20,
                        5.0f,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "SURVIVAL",
                        false))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inventoryNbt");
    }

    @Test
    @DisplayName("defensively copies mutable byte arrays")
    void defensivelyCopiesArrays() {
        byte[] originalInv = new byte[] {10, 20, 30};
        byte[] originalEc = new byte[] {40, 50, 60};
        byte[] originalPotion = new byte[] {70, 80};

        ProfileInventoryRecord record = new ProfileInventoryRecord(
                PROFILE_ID,
                1L,
                originalInv,
                originalEc,
                100,
                18.5,
                19,
                4.5f,
                originalPotion,
                "world",
                1.0,
                64.0,
                2.0,
                "SURVIVAL",
                false);

        // Mutate originals
        originalInv[0] = 99;
        originalEc[0] = 99;
        originalPotion[0] = 99;

        assertThat(record.inventoryNbt()[0]).isEqualTo((byte) 10);
        assertThat(record.enderchestNbt()[0]).isEqualTo((byte) 40);
        byte[] potion = record.activePotionEffectsNbt();
        assertThat(potion).isNotNull();
        assertThat(potion[0]).isEqualTo((byte) 70);

        // Mutate retrieved
        byte[] retrieved = record.inventoryNbt();
        retrieved[0] = 77;
        assertThat(record.inventoryNbt()[0]).isEqualTo((byte) 10);
    }

    @Test
    @DisplayName("createDefault creates valid initial record with version 1")
    void createDefaultWorks() {
        ProfileInventoryRecord record = ProfileInventoryRecord.createDefault(PROFILE_ID, INV_BYTES, EC_BYTES);
        assertThat(record.profileId()).isEqualTo(PROFILE_ID);
        assertThat(record.version()).isEqualTo(1L);
        assertThat(record.inventoryNbt()).isEqualTo(INV_BYTES);
        assertThat(record.enderchestNbt()).isEqualTo(EC_BYTES);
        assertThat(record.health()).isEqualTo(20.0);
        assertThat(record.foodLevel()).isEqualTo(20);
        assertThat(record.saturation()).isEqualTo(5.0f);
        assertThat(record.gamemode()).isEqualTo("SURVIVAL");
        assertThat(record.flightAllowed()).isFalse();
    }

    @Test
    @DisplayName("withInventoryNbt advances version and updates payload")
    void withInventoryNbtAdvancesVersion() {
        ProfileInventoryRecord original = ProfileInventoryRecord.createDefault(PROFILE_ID, INV_BYTES, EC_BYTES);
        byte[] updatedPayload = new byte[] {9, 8, 7};
        ProfileInventoryRecord updated = original.withInventoryNbt(2L, updatedPayload);

        assertThat(updated.version()).isEqualTo(2L);
        assertThat(updated.inventoryNbt()).isEqualTo(updatedPayload);
        assertThat(updated.enderchestNbt()).isEqualTo(EC_BYTES);
    }

    @Test
    @DisplayName("equals and hashCode compare byte array contents")
    void equalsAndHashCodeWork() {
        ProfileInventoryRecord a =
                ProfileInventoryRecord.createDefault(PROFILE_ID, new byte[] {1, 2}, new byte[] {3, 4});
        ProfileInventoryRecord b =
                ProfileInventoryRecord.createDefault(PROFILE_ID, new byte[] {1, 2}, new byte[] {3, 4});

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
