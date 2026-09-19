package com.uxplima.uxmskyblock.core.application.activity;

import java.util.List;

import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;

/**
 * Storage port for user-facing activity events (Section 2.19 & 2.42).
 */
public interface ActivityFeedStoragePort {

    void appendEvent(ActivityEvent event);

    List<ActivityEvent> findEventsByInstanceId(String instanceId, int limit);
}
