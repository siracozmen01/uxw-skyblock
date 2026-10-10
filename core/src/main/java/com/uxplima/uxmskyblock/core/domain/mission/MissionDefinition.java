package com.uxplima.uxmskyblock.core.domain.mission;

import java.util.Objects;

public record MissionDefinition(
        MissionId id,
        MissionBranch branch,
        String displayName,
        String description,
        MissionTriggerType triggerType,
        String targetFilter,
        long requiredAmount,
        MissionReward reward,
        MissionRepeat repeat) {

    public MissionDefinition {
        Objects.requireNonNull(repeat, "repeat must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(triggerType, "triggerType must not be null");
        targetFilter = (targetFilter == null) ? "" : targetFilter.trim();
        if (requiredAmount <= 0) {
            throw new IllegalArgumentException("requiredAmount must be positive: " + requiredAmount);
        }
        reward = (reward == null) ? MissionReward.empty() : reward;
    }

    /** A mission finished once, as every mission was before missions came back. */
    public MissionDefinition(
            MissionId id,
            MissionBranch branch,
            String displayName,
            String description,
            MissionTriggerType triggerType,
            String targetFilter,
            long requiredAmount,
            MissionReward reward) {
        this(
                id,
                branch,
                displayName,
                description,
                triggerType,
                targetFilter,
                requiredAmount,
                reward,
                MissionRepeat.ONCE);
    }
}
