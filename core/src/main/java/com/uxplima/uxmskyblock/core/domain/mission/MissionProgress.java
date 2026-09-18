package com.uxplima.uxmskyblock.core.domain.mission;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record MissionProgress(
        MissionId missionId,
        long progressCount,
        boolean completed,
        @Nullable Instant completedAt,
        Instant updatedAt) {

    public MissionProgress {
        Objects.requireNonNull(missionId, "missionId must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (progressCount < 0) {
            throw new IllegalArgumentException("progressCount must not be negative: " + progressCount);
        }
    }

    public static MissionProgress initial(MissionId missionId) {
        return new MissionProgress(missionId, 0L, false, null, Instant.now());
    }

    public MissionProgress increment(long delta, long requiredAmount, Instant now) {
        if (completed) {
            return this;
        }
        long newCount = this.progressCount + Math.max(0L, delta);
        boolean isNowCompleted = newCount >= requiredAmount;
        return new MissionProgress(
                this.missionId,
                newCount,
                isNowCompleted,
                isNowCompleted ? (completedAt != null ? completedAt : now) : null,
                now);
    }
}
