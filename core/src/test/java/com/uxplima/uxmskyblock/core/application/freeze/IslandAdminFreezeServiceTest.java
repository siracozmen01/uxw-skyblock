package com.uxplima.uxmskyblock.core.application.freeze;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.OutboxClaim;
import com.uxplima.uxmskyblock.core.domain.event.OutboxEventRecord;
import com.uxplima.uxmskyblock.core.domain.freeze.IslandFreezeRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.AdministrativeState;
import com.uxplima.uxmskyblock.core.domain.island.EconomicState;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLifecycle;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandAdminFreezeServiceTest {

    private InMemoryIslandStoragePort islandStoragePort;
    private InMemoryFreezePort freezeStoragePort;
    private TestVisitorEvictionPort visitorEvictionPort;
    private InMemoryOutboxPort outboxPort;
    private IslandAdminFreezeService service;

    private IslandId islandId;
    private PlayerUuid ownerUuid;
    private ProfileId ownerProfileId;
    private Island testIsland;
    private IslandLocation testLocation;

    @BeforeEach
    void setUp() {
        islandStoragePort = new InMemoryIslandStoragePort();
        freezeStoragePort = new InMemoryFreezePort();
        visitorEvictionPort = new TestVisitorEvictionPort();
        outboxPort = new InMemoryOutboxPort();

        service = new IslandAdminFreezeService(islandStoragePort, freezeStoragePort, visitorEvictionPort, outboxPort);

        islandId = IslandId.of(UUID.randomUUID());
        ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ownerProfileId = ProfileId.of(ownerUuid.value());

        testIsland = Island.create(
                islandId, IslandBounds.fromCenterAndRadius(0, 0, 100), ownerUuid, ownerProfileId, Instant.now());
        testLocation = new IslandLocation(islandId, "world", testIsland.bounds(), 0, 100, 0, 0, 0);

        islandStoragePort.saveIsland(testIsland, testLocation);
    }

    @Test
    @DisplayName("freezing an island sets FROZEN state, saves rationale, triggers eviction, and stages outbox event")
    void freezeIslandSuccess() {
        boolean result = service.freezeIsland(islandId, "Duping investigation", "OperatorAlex");

        assertThat(result).isTrue();
        assertThat(service.isFrozen(islandId)).isTrue();
        assertThat(freezeStoragePort.findFreezeRecord(islandId)).isPresent();
        assertThat(freezeStoragePort.findFreezeRecord(islandId).get().freezeReason())
                .isEqualTo("Duping investigation");

        // Visitor eviction verification
        assertThat(visitorEvictionPort.evictedIslands).containsEntry(islandId, "Duping investigation");

        // Outbox event verification
        assertThat(outboxPort.stagedEvents).hasSize(1);
        assertThat(outboxPort.stagedEvents.get(0).eventType).isEqualTo("ISLAND_FROZEN");
    }

    @Test
    @DisplayName("unfreezing an island restores NORMAL state and preserves orthogonal economic state")
    void unfreezeIslandSuccessPreservesEconomicState() {
        // Set up island in BANKRUPTCY_LOCKED and FROZEN
        service.transitionEconomicState(islandId, EconomicState.BANKRUPTCY_GRACE);
        service.transitionEconomicState(islandId, EconomicState.BANKRUPTCY_LOCKED);
        service.freezeIsland(islandId, "Suspicious transactions", "OperatorBob");

        assertThat(service.isFrozen(islandId)).isTrue();
        Island frozen = islandStoragePort.findIslandById(islandId).orElseThrow();
        assertThat(frozen.economicState()).isEqualTo(EconomicState.BANKRUPTCY_LOCKED);

        // Unfreeze
        boolean unfreezeResult = service.unfreezeIsland(islandId, "OperatorBob");
        assertThat(unfreezeResult).isTrue();
        assertThat(service.isFrozen(islandId)).isFalse();

        Island unfrozen = islandStoragePort.findIslandById(islandId).orElseThrow();
        assertThat(unfrozen.administrativeState()).isEqualTo(AdministrativeState.NORMAL);
        assertThat(unfrozen.freezeReason()).isNull();
        // Crucial invariant: EconomicState remains BANKRUPTCY_LOCKED!
        assertThat(unfrozen.economicState()).isEqualTo(EconomicState.BANKRUPTCY_LOCKED);

        assertThat(outboxPort.stagedEvents.stream().anyMatch(e -> e.eventType.equals("ISLAND_UNFROZEN")))
                .isTrue();
    }

    @Test
    @DisplayName("freezing an archived or deleting island is strictly rejected")
    void freezeRejectedWhenNotOperational() {
        service.transitionLifecycle(islandId, IslandLifecycle.ARCHIVED);

        assertThatThrownBy(() -> service.freezeIsland(islandId, "Rule violation", "Staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    @DisplayName("unfreezing an archived island is strictly rejected")
    void unfreezeRejectedWhenNotOperational() {
        service.transitionLifecycle(islandId, IslandLifecycle.ARCHIVED);

        assertThatThrownBy(() -> service.unfreezeIsland(islandId, "Staff"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    @DisplayName("economic state escalation invariant: NORMAL to BANKRUPTCY_LOCKED is forbidden without GRACE")
    void economicEscalationInvariantEnforced() {
        assertThatThrownBy(() -> service.transitionEconomicState(islandId, EconomicState.BANKRUPTCY_LOCKED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid economic state transition");
    }

    @Test
    @DisplayName("economic remediation invariant: BANKRUPTCY_LOCKED can directly recover to NORMAL")
    void economicRemediationAllowed() {
        service.transitionEconomicState(islandId, EconomicState.BANKRUPTCY_GRACE);
        service.transitionEconomicState(islandId, EconomicState.BANKRUPTCY_LOCKED);

        // Instant remediation upon settling debt
        service.transitionEconomicState(islandId, EconomicState.NORMAL);

        Island updated = islandStoragePort.findIslandById(islandId).orElseThrow();
        assertThat(updated.economicState()).isEqualTo(EconomicState.NORMAL);
    }

    @Test
    @DisplayName("economic state mutation rejected when island is archived")
    void economicStateMutationRejectedWhenArchived() {
        service.transitionLifecycle(islandId, IslandLifecycle.ARCHIVED);

        assertThatThrownBy(() -> service.transitionEconomicState(islandId, EconomicState.BANKRUPTCY_GRACE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCHIVED");
    }

    @Test
    @DisplayName("transitionLifecycle updates lifecycle state in storage")
    void transitionLifecycleUpdatesState() {
        service.transitionLifecycle(islandId, IslandLifecycle.RECYCLING);

        Island updated = islandStoragePort.findIslandById(islandId).orElseThrow();
        assertThat(updated.lifecycle()).isEqualTo(IslandLifecycle.RECYCLING);
    }

    // --- In-Memory Test Doubles ---

    private static class InMemoryIslandStoragePort implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new HashMap<>();
        private final Map<IslandId, IslandLocation> locations = new HashMap<>();

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            islands.put(island.id(), island);
            locations.put(island.id(), location);
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
            return islands.values().stream()
                    .filter(i -> i.members().containsKey(profileId))
                    .map(Island::id)
                    .findFirst();
        }

        @Override
        public void deleteIsland(IslandId id) {
            islands.remove(id);
            locations.remove(id);
        }
    }

    private static class InMemoryFreezePort implements IslandAdminFreezePort {
        private final Map<IslandId, IslandFreezeRecord> freezeRecords = new HashMap<>();

        @Override
        public void updateAdministrativeState(
                IslandId islandId, AdministrativeState state, @Nullable String freezeReason) {
            freezeRecords.put(islandId, new IslandFreezeRecord(islandId, state, freezeReason, "test", Instant.now()));
        }

        @Override
        public void updateEconomicState(IslandId islandId, EconomicState state) {
            // No-op for in-memory double
        }

        @Override
        public void updateLifecycle(IslandId islandId, IslandLifecycle lifecycle) {
            // No-op for in-memory double
        }

        @Override
        public Optional<IslandFreezeRecord> findFreezeRecord(IslandId islandId) {
            return Optional.ofNullable(freezeRecords.get(islandId));
        }
    }

    private static class TestVisitorEvictionPort implements IslandVisitorEvictionPort {
        private final Map<IslandId, String> evictedIslands = new HashMap<>();

        @Override
        public void evictNonStaffVisitors(IslandId islandId, String reason) {
            evictedIslands.put(islandId, reason);
        }
    }

    private static class InMemoryOutboxPort implements OutboxPort {
        record StagedEvent(EventId eventId, String eventType, String aggregateId, String payload) {}

        private final List<StagedEvent> stagedEvents = new ArrayList<>();

        @Override
        public void stageEvent(EventId eventId, String eventType, String aggregateId, String payload) {
            stagedEvents.add(new StagedEvent(eventId, eventType, aggregateId, payload));
        }

        @Override
        public OutboxClaim claimPendingBatch(String workerId, Duration leaseDuration, int batchSize) {
            return null;
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
            return stagedEvents.size();
        }
    }
}
