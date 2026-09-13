package com.uxplima.uxmskyblock.core.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly typed identity value object for a player profile identity.
 *
 * <p>Distinct from the physical Minecraft player UUID ({@link PlayerUuid}) and island aggregate
 * identity ({@link IslandId}).
 *
 * @param value the underlying profile UUID
 */
public record ProfileId(UUID value) {

    public ProfileId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ProfileId of(UUID value) {
        return new ProfileId(value);
    }

    public static ProfileId fromString(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return new ProfileId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
