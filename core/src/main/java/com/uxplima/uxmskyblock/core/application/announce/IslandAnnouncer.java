package com.uxplima.uxmskyblock.core.application.announce;

import java.util.Map;

import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;

/**
 * Somewhere outside the server that wants to be told what happened.
 *
 * <p>The Discord webhook service was built, given a URL per topic, rate limited and queued, and
 * nothing ever told it anything: not one of its four notification methods had a caller. The
 * architecture names Discord webhooks a version one requirement.
 *
 * <p>The services that know these things happened should not know what Discord is, so they are
 * handed one of these and told nothing more. A node with nowhere to announce to has none.
 */
public interface IslandAnnouncer {

    /**
     * Two islands became allies, or stopped being allies.
     *
     * @param allianceName what to call the pair
     * @param action what happened, in a word an operator reads
     * @param actorName who did it
     * @param targetName who it was done with
     */
    void notifyAlliance(String allianceName, String action, String actorName, String targetName);

    /**
     * An administrator did something worth a record.
     *
     * @param eventType what was done
     * @param severity how much it matters
     * @param description the sentence a reader sees
     * @param details whatever else is worth carrying, by name
     */
    void notifyAdminAudit(String eventType, String severity, String description, Map<String, String> details);

    /**
     * The islands at the top of a board, as they stand.
     *
     * @param metricName what the board ranks by, in a word a reader understands
     * @param topEntries the leading islands, best first
     */
    void notifyLeaderboard(String metricName, java.util.List<LeaderboardEntry> topEntries);
}
