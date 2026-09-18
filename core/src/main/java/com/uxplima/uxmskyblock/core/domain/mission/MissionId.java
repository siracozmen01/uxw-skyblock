package com.uxplima.uxmskyblock.core.domain.mission;

import java.util.Locale;
import java.util.Objects;

public record MissionId(String value) {
    public MissionId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("MissionId must not be blank");
        }
    }

    public static MissionId of(String value) {
        return new MissionId(value.toLowerCase(Locale.ROOT).trim());
    }

    @Override
    public String toString() {
        return value;
    }
}
