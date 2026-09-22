package com.uxplima.uxmskyblock.core.application.announce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceStoragePort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezePort;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteId;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Something worth announcing is announced.
 *
 * <p>The Discord webhook service was built, given a URL per topic, rate limited and queued, and
 * nothing ever told it anything: not one of its four notification methods had a caller anywhere.
 * The architecture names Discord webhooks a version one requirement.
 *
 * <p>An announcement is told after the write and can never undo it. A listener that throws is the
 * case worth pinning: an alliance that stands or a freeze that holds must not be rolled back
 * because somebody's webhook was down.
 */
class SomethingWorthAnnouncingIsAnnouncedTest {

    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    /** Writes down what it was told, and can be made to throw. */
    private static final class Recorder implements IslandAnnouncer {
        private final List<String> heard = new ArrayList<>();
        private boolean throwing;

        @Override
        public void notifyAlliance(String allianceName, String action, String actorName, String targetName) {
            if (throwing) {
                throw new IllegalStateException("the webhook is down");
            }
            heard.add("alliance:" + action);
        }

        @Override
        public void notifyAdminAudit(
                String eventType, String severity, String description, Map<String, String> details) {
            if (throwing) {
                throw new IllegalStateException("the webhook is down");
            }
            heard.add("audit:" + eventType + ":" + severity);
        }

        @Override
        public void notifyLeaderboard(
                String metricName, List<com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry> topEntries) {
            heard.add("leaderboard:" + metricName);
        }
    }

    /**
     * A service holding one live invite between these two islands.
     *
     * <p>The invite window is built around the clock this runs on, because accepting one reads that
     * clock. It was written around a fixed date instead, so these tests passed until that date's
     * five minutes ran out and then failed on every machine, for good.
     */
    private static IslandAllianceService allianceServiceThatAccepts(IslandId a, IslandId b) {
        IslandAllianceStoragePort storage = mock(IslandAllianceStoragePort.class);
        Instant issued = Instant.now();
        when(storage.findInvite(a, b))
                .thenReturn(Optional.of(new IslandAllianceInvite(
                        AllianceInviteId.random(),
                        a,
                        b,
                        new ProfileId(UUID.randomUUID()),
                        issued,
                        issued.plusSeconds(300))));
        when(storage.countAlliances(any())).thenReturn(0);
        return new IslandAllianceService(storage);
    }

    private static IslandAdminFreezeService freezeServiceOver(IslandStoragePort storage) {
        return new IslandAdminFreezeService(storage, mock(IslandAdminFreezePort.class), null, null);
    }

    @Test
    @DisplayName("An alliance that is formed is announced, once, and says so")
    void formingAnAllianceIsAnnounced() {
        IslandId a = IslandId.of(UUID.randomUUID());
        IslandId b = IslandId.of(UUID.randomUUID());
        IslandAllianceService service = allianceServiceThatAccepts(a, b);
        Recorder recorder = new Recorder();
        service.setAnnouncer(recorder);

        service.acceptInvite(a, b);

        assertThat(recorder.heard).containsExactly("alliance:ALLIANCE_FORMED");
    }

    @Test
    @DisplayName("An alliance that is dissolved is announced")
    void dissolvingAnAllianceIsAnnounced() {
        IslandAllianceService service = new IslandAllianceService(mock(IslandAllianceStoragePort.class));
        Recorder recorder = new Recorder();
        service.setAnnouncer(recorder);

        service.removeAlliance(IslandId.of(UUID.randomUUID()), IslandId.of(UUID.randomUUID()));

        assertThat(recorder.heard).containsExactly("alliance:ALLIANCE_DISSOLVED");
    }

    @Test
    @DisplayName("A node with nowhere to announce to carries on without one")
    void noAnnouncerIsNotAFailure() {
        IslandId a = IslandId.of(UUID.randomUUID());
        IslandId b = IslandId.of(UUID.randomUUID());

        assertThat(allianceServiceThatAccepts(a, b).acceptInvite(a, b)).isNotNull();
    }

    @Test
    @DisplayName("An announcement that throws does not undo the alliance")
    void aThrowingAnnouncerDoesNotUndoTheWrite() {
        IslandId a = IslandId.of(UUID.randomUUID());
        IslandId b = IslandId.of(UUID.randomUUID());
        IslandAllianceService service = allianceServiceThatAccepts(a, b);
        Recorder recorder = new Recorder();
        recorder.throwing = true;
        service.setAnnouncer(recorder);

        assertThat(service.acceptInvite(a, b))
                .describedAs("an alliance must not be rolled back because a webhook was down")
                .isNotNull();
    }

    @Test
    @DisplayName("Freezing and unfreezing an island are both announced, with what they are worth")
    void administrativeChangesAreAnnounced() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        ProfileId owner = new ProfileId(UUID.randomUUID());
        Island island = Island.create(
                islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), new PlayerUuid(owner.value()), owner, NOW);

        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(storage.findLocationByIslandId(islandId))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(islandId, "world", 0, 0, 50)));

        IslandAdminFreezeService service = freezeServiceOver(storage);
        Recorder recorder = new Recorder();
        service.setAnnouncer(recorder);

        service.freezeIsland(islandId, "for testing", "an admin");
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island.freeze("for testing")));
        service.unfreezeIsland(islandId, "an admin");

        assertThat(recorder.heard).containsExactly("audit:ISLAND_FROZEN:high", "audit:ISLAND_UNFROZEN:medium");
    }

    @Test
    @DisplayName("An announcement that throws does not undo the freeze")
    void aThrowingAnnouncerDoesNotUndoTheFreeze() {
        IslandId islandId = IslandId.of(UUID.randomUUID());
        ProfileId owner = new ProfileId(UUID.randomUUID());
        Island island = Island.create(
                islandId, IslandBounds.fromCenterAndRadius(0, 0, 50), new PlayerUuid(owner.value()), owner, NOW);

        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(storage.findLocationByIslandId(islandId))
                .thenReturn(Optional.of(IslandLocation.fromCenterAndRadius(islandId, "world", 0, 0, 50)));

        IslandAdminFreezeService service = freezeServiceOver(storage);
        Recorder recorder = new Recorder();
        recorder.throwing = true;
        service.setAnnouncer(recorder);

        assertThat(service.freezeIsland(islandId, "for testing", "an admin"))
                .describedAs("a freeze must hold even when the webhook is down")
                .isTrue();
    }
}
