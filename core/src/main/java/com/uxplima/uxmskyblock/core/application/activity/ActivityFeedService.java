package com.uxplima.uxmskyblock.core.application.activity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Domain application service recording and reading user-facing activity digests (Section 2.19 & 2.42).
 */
public final class ActivityFeedService {

    private final ActivityFeedStoragePort storagePort;

    public ActivityFeedService(ActivityFeedStoragePort storagePort) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
    }

    public ActivityEvent recordActivity(
            String instanceId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String payloadTypeId,
            int payloadSchemaVersion,
            String payloadData) {

        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(visibility, "visibility must not be null");
        Objects.requireNonNull(payloadTypeId, "payloadTypeId must not be null");
        Objects.requireNonNull(payloadData, "payloadData must not be null");

        ActivityEvent event = new ActivityEvent(
                UUID.randomUUID(),
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                payloadTypeId,
                payloadSchemaVersion,
                payloadData,
                Instant.now());

        storagePort.appendEvent(event);
        return event;
    }

    public List<ActivityEvent> getRecentActivities(String instanceId, int limit) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        return storagePort.findEventsByInstanceId(instanceId, Math.max(1, limit));
    }
}
