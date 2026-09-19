package com.uxplima.uxmskyblock.core.domain.dimension;

import java.util.Locale;
import java.util.Objects;

/**
 * Value object representing a world dimension identifier (e.g. overworld, the_nether, the_end).
 */
public record DimensionId(String value) {
    public static final DimensionId OVERWORLD = new DimensionId("overworld");
    public static final DimensionId THE_NETHER = new DimensionId("the_nether");
    public static final DimensionId THE_END = new DimensionId("the_end");

    public DimensionId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("DimensionId value must not be blank");
        }
        value = value.toLowerCase(Locale.ROOT).trim();
    }

    public static DimensionId of(String value) {
        return new DimensionId(value);
    }
}
