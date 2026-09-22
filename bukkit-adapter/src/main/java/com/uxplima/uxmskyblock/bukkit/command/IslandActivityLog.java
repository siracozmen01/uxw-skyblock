package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityVisibility;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Where a command writes down what it just did to an island.
 *
 * <p>The feed, its table, its twelve event types and {@code /is activity} have been here since the
 * activity work, and nothing ever wrote a row: recordActivity had no caller anywhere, so every
 * island's feed was empty for as long as the server ran.
 *
 * <p>Every writer goes through this so none of them has to know that the service may be absent, and
 * so a feed that will not write never takes down the thing that actually happened. The island
 * change already stands by the time the line is written; failing to write the line is worth a
 * warning in the log and nothing more.
 */
public final class IslandActivityLog {

    private static final Logger LOGGER = Logger.getLogger(IslandActivityLog.class.getName());

    private volatile @Nullable ActivityFeedService service;

    /** Tells the log where to write. Absent until the plugin is wired, and on a node without it. */
    public void useService(@Nullable ActivityFeedService service) {
        this.service = service;
    }

    /** Whether anything would be written at all. */
    public boolean isWriting() {
        return service != null;
    }

    /**
     * Writes one line of an island's feed.
     *
     * @param messageKey the message in the operator's catalogue, never a sentence
     * @param values what that message has holes for
     */
    public void record(
            IslandId islandId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            ActivityVisibility visibility,
            String messageKey,
            Map<String, String> values) {
        ActivityFeedService writing = this.service;
        if (writing == null) {
            return;
        }
        try {
            writing.record(islandId.value().toString(), actorProfileId, eventType, visibility, messageKey, values);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "Writing a " + eventType + " line on island " + islandId + " failed.");
        }
    }

    /** The common case: something the island's members may see. */
    public void recordForMembers(
            IslandId islandId,
            @Nullable ProfileId actorProfileId,
            ActivityEventType eventType,
            String messageKey,
            Map<String, String> values) {
        record(islandId, actorProfileId, eventType, ActivityVisibility.MEMBERS_ONLY, messageKey, values);
    }
}
