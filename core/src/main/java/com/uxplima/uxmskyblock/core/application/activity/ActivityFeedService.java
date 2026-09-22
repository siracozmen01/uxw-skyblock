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

    /**
     * Writes down something that happened on an island, for whoever looks at its feed later.
     *
     * <p>The feed, its table, its twelve event types and the command that reads it have been here
     * since the activity work, and nothing ever wrote a row: recordActivity had no caller anywhere,
     * so every island's feed was empty for as long as the server ran.
     *
     * @param messageKey the message in the operator's catalogue, never a sentence
     * @param values what that message has holes for
     */
    public ActivityEvent record(
            String instanceId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String messageKey,
            java.util.Map<String, String> values) {
        Objects.requireNonNull(messageKey, "messageKey must not be null");
        Objects.requireNonNull(values, "values must not be null");
        return recordActivity(
                instanceId,
                actorProfileId,
                eventType,
                visibility,
                messageKey,
                1,
                com.uxplima.uxmskyblock.core.domain.message.MessagePayload.pack(values));
    }

    /**
     * Drops the events an island's feed has outgrown.
     *
     * <p>Nothing ever deleted one, which is the other half of a table that has finally started
     * being written to.
     *
     * @return how many were deleted
     */
    public int purgeOlderThan(Instant before) {
        Objects.requireNonNull(before, "before must not be null");
        return storagePort.purgeEventsBefore(before);
    }

    public List<ActivityEvent> getRecentActivities(String instanceId, int limit) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        return storagePort.findEventsByInstanceId(instanceId, Math.max(1, limit));
    }
}
