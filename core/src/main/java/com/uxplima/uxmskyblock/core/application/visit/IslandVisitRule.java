package com.uxplima.uxmskyblock.core.application.visit;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import org.jspecify.annotations.Nullable;

/**
 * Whether a player may be put down on somebody else's island.
 *
 * <p>The warp path has asked this since it was written: a ban and the LOCKED flag both stop a
 * visitor there. {@code /is visit} asked nothing at all, so the same island refused a player at one
 * door and let them in at the other, and an island archived for inactivity is LOCKED by the
 * inactivity service and was open to anybody who typed the owner's name.
 *
 * <p>VISITOR_ACCESS was worse. The Bedrock settings form offers it, the operator's menu offers it,
 * the flag is stored and read back, and nothing in the plugin ever consulted it. An owner who
 * turned visitors off watched them arrive.
 *
 * <p>This is a rule over facts a caller has already read, not a service with a port. The caller
 * knows the island, whether the visitor is banned and whether an alliance grants them access; the
 * decision is arithmetic on those three and belongs where both callers can reach it.
 */
public final class IslandVisitRule {

    /** What a request to visit came back with. */
    public sealed interface Decision {

        /** The visitor may be put down. */
        record Allowed() implements Decision {}

        /** The island's owner banned this player from it. */
        record Banned() implements Decision {}

        /** The island is locked: to everybody but its members and its privileged allies. */
        record Locked() implements Decision {}

        /** The island's owner turned VISITOR_ACCESS off. */
        record ClosedToVisitors() implements Decision {}
    }

    private IslandVisitRule() {}

    /**
     * Decides whether {@code visitorProfileId} may be put down on {@code island}.
     *
     * <p>A member of the island is never refused, whatever the flags say, because the flags are
     * theirs. A privileged ally passes the lock the same way the warp path lets them.
     *
     * @param island the island being visited
     * @param visitorProfileId the visitor's profile, or null when they have no session
     * @param banned whether the island's ban list names this player
     * @param privilegedAlly whether an alliance grants this profile access to this island
     */
    public static Decision decide(
            Island island, @Nullable ProfileId visitorProfileId, boolean banned, boolean privilegedAlly) {
        Objects.requireNonNull(island, "island must not be null");

        boolean member = visitorProfileId != null && island.isMember(visitorProfileId);
        if (member) {
            return new Decision.Allowed();
        }
        if (banned) {
            return new Decision.Banned();
        }
        if (privilegedAlly) {
            return new Decision.Allowed();
        }
        if (island.flags().isEnabled(IslandFlags.LOCKED)) {
            return new Decision.Locked();
        }
        if (!island.flags().isEnabled(IslandFlags.VISITOR_ACCESS)) {
            return new Decision.ClosedToVisitors();
        }
        return new Decision.Allowed();
    }
}
