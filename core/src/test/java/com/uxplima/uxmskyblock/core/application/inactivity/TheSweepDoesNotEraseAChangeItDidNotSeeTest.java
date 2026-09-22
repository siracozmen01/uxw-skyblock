package com.uxplima.uxmskyblock.core.application.inactivity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.AbandonmentAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.FormerOwnerAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The inactivity sweep does not write back the island it read at the start of the sweep.
 *
 * <p>The sweep is handed every island in a world at once and then works through them one at a time.
 * It read the whole island, changed one part of it and wrote the whole thing back, holding nothing,
 * so a member who joined while the sweep was running was erased by it. The lock that exists for
 * exactly this said in its own description that this service held it, and the guard that enforces
 * it excused this file on the claim that a sweep only erases islands. It archives them and it
 * transfers ownership.
 */
class TheSweepDoesNotEraseAChangeItDidNotSeeTest {

    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");

    private static final InactivityPolicy ARCHIVE_ABANDONED = new InactivityPolicy(
            true,
            Duration.ofDays(30),
            Duration.ofDays(60),
            List.of(IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER),
            FormerOwnerAction.DEMOTE_TO_CO_OWNER,
            AbandonmentAction.ARCHIVE);

    /** Remembers islands, and counts how often each is read by its identifier. */
    private static final class Storage implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new ConcurrentHashMap<>();
        private final Map<IslandId, IslandLocation> locations = new ConcurrentHashMap<>();
        private final AtomicInteger readsById = new AtomicInteger();

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            saveIsland(island, location, null);
        }

        @Override
        public void saveIsland(Island island, IslandLocation location, @Nullable StagedOutboxEvent outboxEvent) {
            islands.put(island.id(), island);
            locations.put(island.id(), location);
        }

        @Override
        public Optional<Island> findIslandById(IslandId id) {
            readsById.incrementAndGet();
            return Optional.ofNullable(islands.get(id));
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
            return Optional.ofNullable(locations.get(id));
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return islands.values().stream()
                    .filter(island -> island.isMember(profileId))
                    .map(Island::id)
                    .findFirst();
        }

        @Override
        public void deleteIsland(IslandId id) {
            islands.remove(id);
            locations.remove(id);
        }
    }

    /** Nobody has been seen for a hundred days, so every island is abandoned. */
    private static final class NobodyIsAround implements PlayerActivityProvider {
        @Override
        public Optional<Instant> getLastActive(PlayerUuid playerUuid, ProfileId profileId) {
            return Optional.of(NOW.minus(Duration.ofDays(100)));
        }
    }

    private static Island islandOwnedBy(Storage storage, ProfileId owner) {
        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()), bounds, PlayerUuid.of(owner.value()), owner, NOW.minusSeconds(1));
        storage.saveIsland(island, new IslandLocation(island.id(), "world", bounds, 0, 100, 0, 0, 0));
        return island;
    }

    @Test
    @DisplayName("A member who joins while the sweep is running is still a member after it")
    void amemberWhoJoinsMidSweepSurvivesIt() {
        Storage storage = new Storage();
        ProfileId owner = ProfileId.of(UUID.randomUUID());
        Island scanned = islandOwnedBy(storage, owner);

        // What the sweep was handed, and what the island became while it worked through the world.
        ProfileId joiner = ProfileId.of(UUID.randomUUID());
        Island afterTheJoin = scanned.addMember(
                new IslandMember(PlayerUuid.of(joiner.value()), joiner, IslandRole.MEMBER, NOW.minusSeconds(1)));
        storage.saveIsland(afterTheJoin, new IslandLocation(scanned.id(), "world", scanned.bounds(), 0, 100, 0, 0, 0));

        IslandInactivityService service = new IslandInactivityService(
                storage, new NobodyIsAround(), ARCHIVE_ABANDONED, null, null, null, new IslandMutationLock());

        service.evaluateAll(List.of(scanned), NOW);

        Island written = storage.findIslandById(scanned.id()).orElseThrow();
        assertThat(written.isMember(joiner))
                .describedAs("the sweep wrote back the members it read at the start and the join was gone")
                .isTrue();
        assertThat(written.flags().isEnabled("ARCHIVED"))
                .describedAs("and it still did what it was sweeping for")
                .isTrue();
    }

    @Test
    @DisplayName("The sweep holds the island while it decides and writes")
    void thesweepHoldsTheIslandWhileItWorks() {
        Storage storage = new Storage();
        ProfileId owner = ProfileId.of(UUID.randomUUID());
        Island island = islandOwnedBy(storage, owner);

        IslandMutationLock lock = new IslandMutationLock();
        AtomicBoolean heldWhileWriting = new AtomicBoolean();
        IslandStoragePort watching = new IslandStoragePort() {
            @Override
            public void saveIsland(Island written, IslandLocation location) {
                saveIsland(written, location, null);
            }

            @Override
            public void saveIsland(Island written, IslandLocation location, @Nullable StagedOutboxEvent event) {
                heldWhileWriting.set(lock.isHeld(written.id()));
                storage.saveIsland(written, location, event);
            }

            @Override
            public Optional<Island> findIslandById(IslandId id) {
                return storage.findIslandById(id);
            }

            @Override
            public Optional<IslandLocation> findLocationByIslandId(IslandId id) {
                return storage.findLocationByIslandId(id);
            }

            @Override
            public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
                return storage.findIslandIdByProfileId(profileId);
            }

            @Override
            public void deleteIsland(IslandId id) {
                storage.deleteIsland(id);
            }
        };

        new IslandInactivityService(watching, new NobodyIsAround(), ARCHIVE_ABANDONED, null, null, null, lock)
                .evaluateAll(List.of(island), NOW);

        assertThat(heldWhileWriting)
                .describedAs("nine services read, change and write an island, and this one held nothing")
                .isTrue();
    }

    @Test
    @DisplayName("The lock the sweep holds is the one the rest of the island holds, or it locks nothing")
    void thelockIsShared() {
        Storage storage = new Storage();
        IslandMutationLock lock = new IslandMutationLock();
        ProfileId owner = ProfileId.of(UUID.randomUUID());
        Island island = islandOwnedBy(storage, owner);

        IslandLocationService locations = new IslandLocationService(storage, lock);
        new IslandInactivityService(storage, new NobodyIsAround(), ARCHIVE_ABANDONED, null, null, null, lock)
                .evaluateAll(List.of(island), NOW);

        assertThat(locations.findIsland(island.id())).isPresent();
        assertThat(lock.held()).describedAs("and it lets go when it is done").isZero();
    }
}
