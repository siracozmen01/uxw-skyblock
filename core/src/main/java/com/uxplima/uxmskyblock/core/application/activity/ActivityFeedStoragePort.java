package com.uxplima.uxmskyblock.core.application.activity;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;

/**
 * Storage port for user-facing activity events (Section 2.19 & 2.42).
 */
public interface ActivityFeedStoragePort {

    void appendEvent(ActivityEvent event);

    List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit);

    /**
     * Deletes the events older than {@code before}.
     *
     * <p>An island's feed is a digest of what happened lately, not a ledger. Nothing ever deleted an
     * event, so the table would have grown for as long as the server ran the moment anything started
     * writing to it.
     *
     * @return how many were deleted
     */
    int purgeEventsBefore(java.time.Instant before);
}
