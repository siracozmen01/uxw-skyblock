package com.uxplima.uxmskyblock.core.domain.mission;

import java.util.List;

public record MissionReward(
        long crystals,
        long currencyMinorUnits,
        long islandExp,
        List<String> commands) {

    public MissionReward {
        if (crystals < 0 || currencyMinorUnits < 0 || islandExp < 0) {
            throw new IllegalArgumentException("Reward amounts cannot be negative");
        }
        commands = (commands == null) ? List.of() : List.copyOf(commands);
    }

    public static MissionReward empty() {
        return new MissionReward(0L, 0L, 0L, List.of());
    }
}
