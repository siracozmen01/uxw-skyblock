package com.uxplima.uxmskyblock.core.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identity value object for a Minecraft player UUID.
 *
 * <p>Distinct from aggregate identities such as {@link ProfileId} or {@link IslandId} to prevent
 * accidental parameter mixing.
 *
 * @param value the underlying Minecraft player UUID
 */
public record PlayerUuid(UUID value) implements Comparable<PlayerUuid> {

    public PlayerUuid {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static final PlayerUuid SYSTEM =
            new PlayerUuid(UUID.nameUUIDFromBytes("uxm:system".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    public static final PlayerUuid WEBSTORE =
            new PlayerUuid(UUID.nameUUIDFromBytes("uxm:webstore".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    public static final PlayerUuid UPKEEP = new PlayerUuid(
            UUID.nameUUIDFromBytes("island-upkeep-actor".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    public static PlayerUuid of(UUID value) {
        return new PlayerUuid(value);
    }

    public static PlayerUuid fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new PlayerUuid(UUID.fromString(value));
    }

    @Override
    public int compareTo(PlayerUuid other) {
        int msbComparison =
                Long.compareUnsigned(this.value.getMostSignificantBits(), other.value.getMostSignificantBits());
        if (msbComparison != 0) {
            return msbComparison;
        }
        return Long.compareUnsigned(this.value.getLeastSignificantBits(), other.value.getLeastSignificantBits());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
