package com.uxplima.uxmskyblock.core.domain.inventory;

import java.util.Arrays;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Immutable domain record representing the stored state of a player profile inventory.
 *
 * <p>All byte array payloads are defensively copied on construction and retrieval.
 */
@SuppressWarnings({"ArrayRecordComponent", "NullAway", "NullablePrimitiveArray"})
public record ProfileInventoryRecord(
        ProfileId profileId,
        long version,
        byte[] inventoryNbt,
        byte[] enderchestNbt,
        int experiencePoints,
        double health,
        int foodLevel,
        float saturation,
        byte @Nullable [] activePotionEffectsNbt,
        @Nullable String logoutWorld,
        @Nullable Double logoutX,
        @Nullable Double logoutY,
        @Nullable Double logoutZ,
        String gamemode,
        boolean flightAllowed) {

    public ProfileInventoryRecord {
        Objects.requireNonNull(profileId, "profileId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive: " + version);
        }
        Objects.requireNonNull(inventoryNbt, "inventoryNbt");
        Objects.requireNonNull(enderchestNbt, "enderchestNbt");
        Objects.requireNonNull(gamemode, "gamemode");
        inventoryNbt = inventoryNbt.clone();
        enderchestNbt = enderchestNbt.clone();
        if (activePotionEffectsNbt != null) {
            activePotionEffectsNbt = activePotionEffectsNbt.clone();
        }
    }

    /**
     * Creates a default initial profile inventory record at version 1 with empty/default gameplay stats.
     *
     * @param profileId the owning profile ID
     * @param inventoryNbt initial inventory NBT bytes
     * @param enderchestNbt initial enderchest NBT bytes
     * @return new default inventory record
     */
    public static ProfileInventoryRecord createDefault(ProfileId profileId, byte[] inventoryNbt, byte[] enderchestNbt) {
        return new ProfileInventoryRecord(
                profileId,
                1L,
                inventoryNbt,
                enderchestNbt,
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
                false);
    }

    /**
     * Derives an updated record with modified inventory NBT and advanced version.
     *
     * @param newVersion the incremented OCC version
     * @param newInventoryNbt the updated inventory payload
     * @return updated record
     */
    public ProfileInventoryRecord withInventoryNbt(long newVersion, byte[] newInventoryNbt) {
        return new ProfileInventoryRecord(
                profileId,
                newVersion,
                newInventoryNbt,
                enderchestNbt,
                experiencePoints,
                health,
                foodLevel,
                saturation,
                activePotionEffectsNbt,
                logoutWorld,
                logoutX,
                logoutY,
                logoutZ,
                gamemode,
                flightAllowed);
    }

    @Override
    public byte[] inventoryNbt() {
        return inventoryNbt.clone();
    }

    @Override
    public byte[] enderchestNbt() {
        return enderchestNbt.clone();
    }

    @Override
    public byte @Nullable [] activePotionEffectsNbt() {
        return activePotionEffectsNbt != null ? activePotionEffectsNbt.clone() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProfileInventoryRecord that)) return false;
        return version == that.version
                && experiencePoints == that.experiencePoints
                && Double.compare(that.health, health) == 0
                && foodLevel == that.foodLevel
                && Float.compare(that.saturation, saturation) == 0
                && flightAllowed == that.flightAllowed
                && Objects.equals(profileId, that.profileId)
                && Arrays.equals(inventoryNbt, that.inventoryNbt)
                && Arrays.equals(enderchestNbt, that.enderchestNbt)
                && Arrays.equals(activePotionEffectsNbt, that.activePotionEffectsNbt)
                && Objects.equals(logoutWorld, that.logoutWorld)
                && Objects.equals(logoutX, that.logoutX)
                && Objects.equals(logoutY, that.logoutY)
                && Objects.equals(logoutZ, that.logoutZ)
                && Objects.equals(gamemode, that.gamemode);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                profileId,
                version,
                experiencePoints,
                health,
                foodLevel,
                saturation,
                logoutWorld,
                logoutX,
                logoutY,
                logoutZ,
                gamemode,
                flightAllowed);
        result = 31 * result + Arrays.hashCode(inventoryNbt);
        result = 31 * result + Arrays.hashCode(enderchestNbt);
        result = 31 * result + Arrays.hashCode(activePotionEffectsNbt);
        return result;
    }
}
