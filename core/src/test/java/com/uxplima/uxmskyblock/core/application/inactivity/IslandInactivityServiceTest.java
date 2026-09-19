package com.uxplima.uxmskyblock.core.application.inactivity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.AbandonmentAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.FormerOwnerAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import com.uxplima.uxmskyblock.core.domain.inactivity.SuccessionOutcome;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandInactivityServiceTest {

    private InMemoryIslandStorage islandStorage;
    private InMemoryPlayerActivityProvider activityProvider;
    private InMemoryArchivalPort archivalPort;
    private InMemoryRecyclePort recyclePort;
    private InMemoryOutboxPort outboxPort;

    private final Instant now = Instant.parse("2026-09-18T12:00:00Z");

    @BeforeEach
    void setUp() {
        islandStorage = new InMemoryIslandStorage();
        activityProvider = new InMemoryPlayerActivityProvider();
        archivalPort = new InMemoryArchivalPort();
        recyclePort = new InMemoryRecyclePort();
        outboxPort = new InMemoryOutboxPort();
        islandStorage.outboxPort = outboxPort;
    }

    private Island createSampleIsland(PlayerUuid ownerUuid, ProfileId ownerProfileId, Instant createdAt) {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        Island island = Island.create(IslandId.of(UUID.randomUUID()), bounds, ownerUuid, ownerProfileId, createdAt);
        IslandLocation location = new IslandLocation(island.id(), "world", bounds, 0, 100, 0, 0, 0);
        islandStorage.saveIsland(island, location);
        return island;
    }

    @Test
    @DisplayName("returns SKIPPED_POLICY_DISABLED when policy is not enabled")
    void disabledPolicyReturnsSkipped() {
        InactivityPolicy disabledPolicy = new InactivityPolicy(
                false,
                Duration.ofDays(30),
                Duration.ofDays(60),
                List.of(IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER),
                FormerOwnerAction.DEMOTE_TO_CO_OWNER,
                AbandonmentAction.ARCHIVE);

        IslandInactivityService service = new IslandInactivityService(islandStorage, activityProvider, disabledPolicy);

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SKIPPED_POLICY_DISABLED);
    }

    @Test
    @DisplayName("returns SKIPPED_ACTIVE_OWNER when owner was active within owner inactivity duration")
    void activeOwnerReturnsSkipped() {
        IslandInactivityService service =
                new IslandInactivityService(islandStorage, activityProvider, InactivityPolicy.defaultPolicy());

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(50)));

        // Owner active 5 days ago (threshold is 30 days)
        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(5)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SKIPPED_ACTIVE_OWNER);
    }

    @Test
    @DisplayName("owner inactive, active co-owner is promoted and former owner demoted to CO_OWNER")
    void inactiveOwnerSuccessionDemotesToCoOwner() {
        IslandInactivityService service = new IslandInactivityService(
                islandStorage,
                activityProvider,
                InactivityPolicy.defaultPolicy(),
                archivalPort,
                recyclePort,
                outboxPort);

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        // Add co-owner
        PlayerUuid coOwnerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId coOwnerProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(coOwnerUuid, coOwnerProfileId, IslandRole.CO_OWNER, now.minus(Duration.ofDays(50))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        // Owner inactive 45 days ago, Co-owner active 2 days ago
        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(45)));
        activityProvider.setLastActive(coOwnerProfileId, now.minus(Duration.ofDays(2)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SUCCESSION_EXECUTED);

        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.ownerProfileId()).isEqualTo(coOwnerProfileId);
        assertThat(updated.roleOf(coOwnerProfileId)).isEqualTo(IslandRole.OWNER);
        assertThat(updated.roleOf(ownerProfileId)).isEqualTo(IslandRole.CO_OWNER);
        assertThat(outboxPort.stagedEventTypes).contains("ISLAND_LEADER_SUCCESSION");
    }

    @Test
    @DisplayName("owner inactive with DEMOTE_TO_MEMBER demotes deposed leader to MEMBER")
    void inactiveOwnerSuccessionDemotesToMember() {
        InactivityPolicy policy = new InactivityPolicy(
                true,
                Duration.ofDays(30),
                Duration.ofDays(60),
                List.of(IslandRole.CO_OWNER, IslandRole.MEMBER),
                FormerOwnerAction.DEMOTE_TO_MEMBER,
                AbandonmentAction.ARCHIVE);

        IslandInactivityService service = new IslandInactivityService(islandStorage, activityProvider, policy);

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        PlayerUuid memberUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId memberProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(memberUuid, memberProfileId, IslandRole.MEMBER, now.minus(Duration.ofDays(50))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(40)));
        activityProvider.setLastActive(memberProfileId, now.minus(Duration.ofDays(1)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SUCCESSION_EXECUTED);

        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.ownerProfileId()).isEqualTo(memberProfileId);
        assertThat(updated.roleOf(ownerProfileId)).isEqualTo(IslandRole.MEMBER);
    }

    @Test
    @DisplayName("owner inactive with KICK_FROM_ISLAND removes deposed leader from membership")
    void inactiveOwnerSuccessionKicksFormerOwner() {
        InactivityPolicy policy = new InactivityPolicy(
                true,
                Duration.ofDays(30),
                Duration.ofDays(60),
                List.of(IslandRole.CO_OWNER, IslandRole.MEMBER),
                FormerOwnerAction.KICK_FROM_ISLAND,
                AbandonmentAction.ARCHIVE);

        IslandInactivityService service = new IslandInactivityService(islandStorage, activityProvider, policy);

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        PlayerUuid coOwnerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId coOwnerProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(coOwnerUuid, coOwnerProfileId, IslandRole.CO_OWNER, now.minus(Duration.ofDays(50))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(40)));
        activityProvider.setLastActive(coOwnerProfileId, now.minus(Duration.ofDays(1)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SUCCESSION_EXECUTED);

        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.ownerProfileId()).isEqualTo(coOwnerProfileId);
        assertThat(updated.isMember(ownerProfileId)).isFalse();
    }

    @Test
    @DisplayName("hierarchy preference selects CO_OWNER over MODERATOR even if MODERATOR joined earlier")
    void hierarchyPreferenceSelectsHighestRole() {
        IslandInactivityService service =
                new IslandInactivityService(islandStorage, activityProvider, InactivityPolicy.defaultPolicy());

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        PlayerUuid modUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId modProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(modUuid, modProfileId, IslandRole.MODERATOR, now.minus(Duration.ofDays(80))));

        PlayerUuid coOwnerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId coOwnerProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(coOwnerUuid, coOwnerProfileId, IslandRole.CO_OWNER, now.minus(Duration.ofDays(30))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(45)));
        activityProvider.setLastActive(modProfileId, now.minus(Duration.ofDays(1)));
        activityProvider.setLastActive(coOwnerProfileId, now.minus(Duration.ofDays(2)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SUCCESSION_EXECUTED);

        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.ownerProfileId()).isEqualTo(coOwnerProfileId);
    }

    @Test
    @DisplayName("tie-breaker selects earlier joined member when both have same role in hierarchy")
    void tieBreakerSelectsSeniorMember() {
        IslandInactivityService service =
                new IslandInactivityService(islandStorage, activityProvider, InactivityPolicy.defaultPolicy());

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        PlayerUuid olderCoOwner = PlayerUuid.of(UUID.randomUUID());
        ProfileId olderCoOwnerId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(olderCoOwner, olderCoOwnerId, IslandRole.CO_OWNER, now.minus(Duration.ofDays(80))));

        PlayerUuid newerCoOwner = PlayerUuid.of(UUID.randomUUID());
        ProfileId newerCoOwnerId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(newerCoOwner, newerCoOwnerId, IslandRole.CO_OWNER, now.minus(Duration.ofDays(20))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(45)));
        activityProvider.setLastActive(olderCoOwnerId, now.minus(Duration.ofDays(2)));
        activityProvider.setLastActive(newerCoOwnerId, now.minus(Duration.ofDays(1)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SUCCESSION_EXECUTED);

        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.ownerProfileId()).isEqualTo(olderCoOwnerId);
    }

    @Test
    @DisplayName("inactive higher-role candidate is skipped in favor of active lower-role candidate")
    void inactiveCandidateSkippedForActiveCandidate() {
        IslandInactivityService service =
                new IslandInactivityService(islandStorage, activityProvider, InactivityPolicy.defaultPolicy());

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        // Co-owner is ALSO inactive (40 days ago)
        PlayerUuid coOwnerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId coOwnerProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(coOwnerUuid, coOwnerProfileId, IslandRole.CO_OWNER, now.minus(Duration.ofDays(50))));

        // Member is active (1 day ago)
        PlayerUuid memberUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId memberProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(memberUuid, memberProfileId, IslandRole.MEMBER, now.minus(Duration.ofDays(30))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(45)));
        activityProvider.setLastActive(coOwnerProfileId, now.minus(Duration.ofDays(40)));
        activityProvider.setLastActive(memberProfileId, now.minus(Duration.ofDays(1)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SUCCESSION_EXECUTED);

        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.ownerProfileId()).isEqualTo(memberProfileId);
    }

    @Test
    @DisplayName(
            "owner inactive and no other active members returns SKIPPED_NO_ELIGIBLE_SUCCESSOR before abandonment threshold")
    void ownerInactiveNoSuccessorReturnsSkipped() {
        IslandInactivityService service =
                new IslandInactivityService(islandStorage, activityProvider, InactivityPolicy.defaultPolicy());

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(50)));

        // Owner inactive 35 days (threshold 30d), but total abandonment threshold is 60d
        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(35)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.SKIPPED_NO_ELIGIBLE_SUCCESSOR);
    }

    @Test
    @DisplayName("total abandonment with ARCHIVE archives and locks the island")
    void totalAbandonmentArchivesIsland() {
        IslandInactivityService service = new IslandInactivityService(
                islandStorage,
                activityProvider,
                InactivityPolicy.defaultPolicy(),
                archivalPort,
                recyclePort,
                outboxPort);

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        PlayerUuid memberUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId memberProfileId = ProfileId.of(UUID.randomUUID());
        island = island.addMember(
                new IslandMember(memberUuid, memberProfileId, IslandRole.MEMBER, now.minus(Duration.ofDays(90))));
        islandStorage.saveIsland(
                island, islandStorage.findLocationByIslandId(island.id()).get());

        // Both members inactive 70 days (threshold is 60 days)
        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(70)));
        activityProvider.setLastActive(memberProfileId, now.minus(Duration.ofDays(70)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.ABANDONED_ARCHIVED);

        assertThat(archivalPort.archivedIslands).contains(island.id());
        Island updated = islandStorage.findIslandById(island.id()).orElseThrow();
        assertThat(updated.flags().isEnabled("ARCHIVED")).isTrue();
        assertThat(updated.flags().isEnabled(IslandFlags.LOCKED)).isTrue();
        assertThat(outboxPort.stagedEventTypes).contains("ISLAND_ARCHIVED");
    }

    @Test
    @DisplayName("total abandonment with DELETE_AND_RECYCLE recycles and deletes the island")
    void totalAbandonmentRecyclesAndDeleteIsland() {
        InactivityPolicy policy = new InactivityPolicy(
                true,
                Duration.ofDays(30),
                Duration.ofDays(60),
                List.of(IslandRole.CO_OWNER, IslandRole.MEMBER),
                FormerOwnerAction.DEMOTE_TO_CO_OWNER,
                AbandonmentAction.DELETE_AND_RECYCLE);

        IslandInactivityService service = new IslandInactivityService(
                islandStorage, activityProvider, policy, archivalPort, recyclePort, outboxPort);

        PlayerUuid ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ProfileId ownerProfileId = ProfileId.of(UUID.randomUUID());
        Island island = createSampleIsland(ownerUuid, ownerProfileId, now.minus(Duration.ofDays(100)));

        activityProvider.setLastActive(ownerProfileId, now.minus(Duration.ofDays(65)));

        SuccessionOutcome outcome = service.evaluateIsland(island.id(), now);
        assertThat(outcome).isEqualTo(SuccessionOutcome.ABANDONED_DELETED);

        assertThat(recyclePort.recycledIslands).contains(island.id());
        assertThat(islandStorage.findIslandById(island.id())).isEmpty();
        assertThat(outboxPort.stagedEventTypes).contains("ISLAND_RECYCLED");
    }

    @Test
    @DisplayName("evaluateAll and scanWorld aggregate report correctly across batch")
    void evaluateAllAggregatesReport() {
        IslandInactivityService service =
                new IslandInactivityService(islandStorage, activityProvider, InactivityPolicy.defaultPolicy());

        // Island 1: Active owner
        PlayerUuid u1 = PlayerUuid.of(UUID.randomUUID());
        ProfileId p1 = ProfileId.of(UUID.randomUUID());
        Island i1 = createSampleIsland(u1, p1, now.minus(Duration.ofDays(10)));
        activityProvider.setLastActive(p1, now.minus(Duration.ofDays(2)));

        // Island 2: Succession executed
        PlayerUuid u2 = PlayerUuid.of(UUID.randomUUID());
        ProfileId p2 = ProfileId.of(UUID.randomUUID());
        PlayerUuid u2m = PlayerUuid.of(UUID.randomUUID());
        ProfileId p2m = ProfileId.of(UUID.randomUUID());
        Island i2 = createSampleIsland(u2, p2, now.minus(Duration.ofDays(50)));
        i2 = i2.addMember(new IslandMember(u2m, p2m, IslandRole.CO_OWNER, now.minus(Duration.ofDays(30))));
        islandStorage.saveIsland(
                i2, islandStorage.findLocationByIslandId(i2.id()).get());
        activityProvider.setLastActive(p2, now.minus(Duration.ofDays(40)));
        activityProvider.setLastActive(p2m, now.minus(Duration.ofDays(1)));

        // Island 3: Abandoned archived
        PlayerUuid u3 = PlayerUuid.of(UUID.randomUUID());
        ProfileId p3 = ProfileId.of(UUID.randomUUID());
        Island i3 = createSampleIsland(u3, p3, now.minus(Duration.ofDays(100)));
        activityProvider.setLastActive(p3, now.minus(Duration.ofDays(80)));

        IslandInactivityScanReport report = service.evaluateAll(List.of(i1, i2, i3), now);

        assertThat(report.totalEvaluated()).isEqualTo(3);
        assertThat(report.successionsExecuted()).isEqualTo(1);
        assertThat(report.islandsArchived()).isEqualTo(1);
        assertThat(report.islandsSkipped()).isEqualTo(1);
        assertThat(report.records()).hasSize(3);

        // Also test scanWorld
        IslandInactivityScanReport worldReport = service.scanWorld("world", now);
        assertThat(worldReport.totalEvaluated()).isEqualTo(3);
    }

    // --- In-Memory Test Fakes ---

    private static class InMemoryIslandStorage implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new ConcurrentHashMap<>();
        private final Map<IslandId, IslandLocation> locations = new ConcurrentHashMap<>();
        @Nullable InMemoryOutboxPort outboxPort;

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            saveIsland(island, location, null);
        }

        @Override
        public void saveIsland(Island island, IslandLocation location, @Nullable StagedOutboxEvent outboxEvent) {
            islands.put(island.id(), island);
            locations.put(island.id(), location);
            if (outboxPort != null && outboxEvent != null) {
                outboxPort.stageEvent(
                        outboxEvent.id(),
                        outboxEvent.eventType(),
                        outboxEvent.aggregateId(),
                        outboxEvent.payload());
            }
        }

        @Override
        public Optional<Island> findIslandById(IslandId id) {
            return Optional.ofNullable(islands.get(id));
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
            return Optional.ofNullable(locations.get(id));
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            for (Island island : islands.values()) {
                if (island.isMember(profileId)) {
                    return Optional.of(island.id());
                }
            }
            return Optional.empty();
        }

        @Override
        public void deleteIsland(IslandId id) {
            deleteIsland(id, null);
        }

        @Override
        public void deleteIsland(IslandId id, @Nullable StagedOutboxEvent outboxEvent) {
            islands.remove(id);
            locations.remove(id);
            if (outboxPort != null && outboxEvent != null) {
                outboxPort.stageEvent(
                        outboxEvent.id(),
                        outboxEvent.eventType(),
                        outboxEvent.aggregateId(),
                        outboxEvent.payload());
            }
        }

        @Override
        public List<Island> findAllByWorld(String worldName) {
            List<Island> result = new ArrayList<>();
            for (Map.Entry<IslandId, IslandLocation> entry : locations.entrySet()) {
                if (entry.getValue().worldName().equalsIgnoreCase(worldName)) {
                    Island isl = islands.get(entry.getKey());
                    if (isl != null) {
                        result.add(isl);
                    }
                }
            }
            return result;
        }
    }

    private static class InMemoryPlayerActivityProvider implements PlayerActivityProvider {
        private final Map<ProfileId, Instant> lastActiveMap = new ConcurrentHashMap<>();

        void setLastActive(ProfileId profileId, Instant lastActive) {
            lastActiveMap.put(profileId, lastActive);
        }

        @Override
        public Optional<Instant> getLastActive(PlayerUuid playerUuid, ProfileId profileId) {
            return Optional.ofNullable(lastActiveMap.get(profileId));
        }
    }

    private static class InMemoryArchivalPort implements IslandArchivalPort {
        final List<IslandId> archivedIslands = new ArrayList<>();

        @Override
        public void archiveIsland(IslandId islandId) {
            archivedIslands.add(islandId);
        }
    }

    private static class InMemoryRecyclePort implements IslandRecyclePort {
        final List<IslandId> recycledIslands = new ArrayList<>();

        @Override
        public void recycleIsland(IslandId islandId) {
            recycledIslands.add(islandId);
        }
    }

    private static class InMemoryOutboxPort implements OutboxPort {
        final List<String> stagedEventTypes = new ArrayList<>();

        @Override
        public void stageEvent(EventId eventId, String eventType, String aggregateId, String payload) {
            stagedEventTypes.add(eventType);
        }

        @Override
        public OutboxClaim claimPendingBatch(String workerId, Duration leaseDuration, int batchSize) {
            return new OutboxClaim(UUID.randomUUID().toString(), workerId, Instant.now(), List.of());
        }

        @Override
        public boolean completeClaim(EventId eventId, String workerId, String claimToken) {
            return true;
        }

        @Override
        public void recordFailure(
                EventId eventId,
                String workerId,
                String claimToken,
                String errorMessage,
                Duration retryBackoff,
                int maxRetries) {}

        @Override
        public Optional<OutboxEventRecord> findById(EventId eventId) {
            return Optional.empty();
        }

        @Override
        public int getPendingCount() {
            return stagedEventTypes.size();
        }
    }
}
