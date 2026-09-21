package com.uxplima.uxmskyblock.core.application.visit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that decides whether a player may be put down on somebody else's island.
 *
 * <p>Two of these were live defects. {@code /is visit} asked nothing at all, so a locked island
 * refused a visitor at the warp door and welcomed them at the visit door. VISITOR_ACCESS was a
 * switch in the settings form that nothing on the server read.
 */
class IslandVisitRuleTest {

    private static final ProfileId OWNER = new ProfileId(UUID.randomUUID());
    private static final ProfileId VISITOR = new ProfileId(UUID.randomUUID());

    private static Island island() {
        return Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                OWNER,
                Instant.now());
    }

    private static Island withFlag(String flag, boolean enabled) {
        Island base = island();
        return base.withFlags(base.flags().withFlag(flag, enabled));
    }

    @Test
    @DisplayName("An open island takes anybody")
    void anOpenIslandTakesAnybody() {
        assertThat(IslandVisitRule.decide(island(), VISITOR, false, false))
                .isInstanceOf(IslandVisitRule.Decision.Allowed.class);
    }

    @Test
    @DisplayName("A locked island refuses a stranger")
    void aLockedIslandRefusesAStranger() {
        assertThat(IslandVisitRule.decide(withFlag(IslandFlags.LOCKED, true), VISITOR, false, false))
                .isInstanceOf(IslandVisitRule.Decision.Locked.class);
    }

    @Test
    @DisplayName("An island closed to visitors refuses a stranger, which is what the switch is for")
    void visitorAccessOffRefusesAStranger() {
        assertThat(IslandVisitRule.decide(withFlag(IslandFlags.VISITOR_ACCESS, false), VISITOR, false, false))
                .isInstanceOf(IslandVisitRule.Decision.ClosedToVisitors.class);
    }

    @Test
    @DisplayName("A banned player is refused even by an island that is open to everybody else")
    void aBannedPlayerIsRefused() {
        assertThat(IslandVisitRule.decide(island(), VISITOR, true, false))
                .isInstanceOf(IslandVisitRule.Decision.Banned.class);
    }

    @Test
    @DisplayName("A member walks through their own island's lock, because the lock is theirs")
    void aMemberIsNeverRefused() {
        assertThat(IslandVisitRule.decide(withFlag(IslandFlags.LOCKED, true), OWNER, false, false))
                .isInstanceOf(IslandVisitRule.Decision.Allowed.class);
        assertThat(IslandVisitRule.decide(withFlag(IslandFlags.VISITOR_ACCESS, false), OWNER, false, false))
                .isInstanceOf(IslandVisitRule.Decision.Allowed.class);
    }

    @Test
    @DisplayName("A privileged ally passes the lock the same way the warp path lets them")
    void aPrivilegedAllyPassesTheLock() {
        assertThat(IslandVisitRule.decide(withFlag(IslandFlags.LOCKED, true), VISITOR, false, true))
                .isInstanceOf(IslandVisitRule.Decision.Allowed.class);
    }

    @Test
    @DisplayName("A ban beats an alliance, the order the warp path already uses")
    void aBanBeatsAnAlliance() {
        assertThat(IslandVisitRule.decide(island(), VISITOR, true, true))
                .isInstanceOf(IslandVisitRule.Decision.Banned.class);
    }

    @Test
    @DisplayName("A visitor with no session is a stranger, not an exception")
    void noSessionIsAStranger() {
        assertThat(IslandVisitRule.decide(withFlag(IslandFlags.LOCKED, true), null, false, false))
                .isInstanceOf(IslandVisitRule.Decision.Locked.class);
        assertThat(IslandVisitRule.decide(island(), null, false, false))
                .isInstanceOf(IslandVisitRule.Decision.Allowed.class);
    }
}
