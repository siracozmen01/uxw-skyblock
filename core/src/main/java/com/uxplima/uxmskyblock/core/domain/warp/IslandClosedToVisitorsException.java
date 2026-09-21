package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The island's owner turned VISITOR_ACCESS off.
 *
 * <p>Separate from {@link IslandLockedException} because the two switches are separate: a locked
 * island is shut to everybody outside it, and an island closed to visitors still takes its allies
 * through the warp path. A visitor who is told the wrong one asks the owner the wrong question.
 */
public class IslandClosedToVisitorsException extends RuntimeException {

    private final IslandId islandId;

    public IslandClosedToVisitorsException(IslandId islandId) {
        super("Island " + islandId + " is closed to visitors");
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
    }

    public IslandId islandId() {
        return islandId;
    }
}
