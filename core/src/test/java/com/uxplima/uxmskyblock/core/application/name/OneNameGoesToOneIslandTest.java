package com.uxplima.uxmskyblock.core.application.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An island name is unique, and eight islands reaching for one at the same moment must leave seven
 * of them disappointed.
 *
 * <p>The service read who held the name, found nobody, and then wrote. Eight callers that read
 * before any of them wrote all found nobody and all wrote, and the server then had one name on eight
 * islands. Every lookup by that name answered with whichever row the database happened to return.
 *
 * <p>The double forces exactly that interleaving with a latch rather than hoping a race turns up on
 * a busy machine. Under the lock only one caller can be looking at a time, so the wait times out and
 * the others go in turn.
 */
class OneNameGoesToOneIslandTest {

    private static final int CLAIMANTS = 8;
    private static final String WANTED = "SkyCitadel";

    /** Holds names the way an adapter without a compare and set would: read, then write. */
    private static final class RacyNameStorage implements IslandNameStoragePort {

        private final Map<String, IslandId> byName = new ConcurrentHashMap<>();
        private final CountDownLatch everybodyLooked;
        final AtomicInteger writes = new AtomicInteger();

        RacyNameStorage(int lookers) {
            this.everybodyLooked = new CountDownLatch(lookers);
        }

        @Override
        public Optional<IslandId> findIslandIdByName(String name) {
            Optional<IslandId> holder = Optional.ofNullable(byName.get(key(name)));
            everybodyLooked.countDown();
            try {
                var unused = everybodyLooked.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return holder;
        }

        @Override
        public boolean claimCustomName(IslandId islandId, IslandName name, @Nullable StagedOutboxEvent outboxEvent) {
            byName.put(key(name.value()), islandId);
            writes.incrementAndGet();
            return true;
        }

        @Override
        public void updateCustomName(IslandId islandId, @Nullable IslandName name) {
            throw new UnsupportedOperationException("The rename path claims; it does not set.");
        }

        @Override
        public Optional<IslandName> findCustomName(IslandId islandId) {
            return Optional.empty();
        }

        private static String key(String name) {
            return name.toLowerCase(Locale.ROOT).trim();
        }
    }

    /** Holds names the way the SQL adapter does: the check and the write are one step. */
    private static final class AtomicNameStorage implements IslandNameStoragePort {

        private final Map<String, IslandId> byName = new ConcurrentHashMap<>();
        private final CountDownLatch everybodyLooked;

        AtomicNameStorage(int lookers) {
            this.everybodyLooked = new CountDownLatch(lookers);
        }

        @Override
        public Optional<IslandId> findIslandIdByName(String name) {
            Optional<IslandId> holder = Optional.ofNullable(byName.get(key(name)));
            everybodyLooked.countDown();
            try {
                var unused = everybodyLooked.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return holder;
        }

        @Override
        public boolean claimCustomName(IslandId islandId, IslandName name, @Nullable StagedOutboxEvent outboxEvent) {
            IslandId holder = byName.putIfAbsent(key(name.value()), islandId);
            return holder == null || holder.equals(islandId);
        }

        @Override
        public void updateCustomName(IslandId islandId, @Nullable IslandName name) {
            throw new UnsupportedOperationException("The rename path claims; it does not set.");
        }

        @Override
        public Optional<IslandName> findCustomName(IslandId islandId) {
            return Optional.empty();
        }

        private static String key(String name) {
            return name.toLowerCase(Locale.ROOT).trim();
        }
    }

    @Test
    @DisplayName("Eight islands reaching for one name leave one holder, even when the storage cannot refuse")
    void eightAtOnceLeaveOneHolder() throws Exception {
        RacyNameStorage storage = new RacyNameStorage(CLAIMANTS);
        IslandNameService service = serviceOver(storage);

        int accepted = renameAllAtOnce(service);

        assertThat(accepted).describedAs("islands told the name was theirs").isEqualTo(1);
        assertThat(storage.writes.get()).describedAs("writes of the name").isEqualTo(1);
    }

    @Test
    @DisplayName("A storage that refuses a taken name leaves one holder on its own")
    void theClaimRefusesOnItsOwn() throws Exception {
        AtomicNameStorage storage = new AtomicNameStorage(CLAIMANTS);
        IslandNameService service = serviceOver(storage);

        assertThat(renameAllAtOnce(service))
                .describedAs("islands told the name was theirs")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Renaming an island to the name it already holds is not a collision")
    void renamingToTheSameNameSucceedsTwice() {
        AtomicNameStorage storage = new AtomicNameStorage(1);
        IslandNameService service = serviceOver(storage);
        IslandId islandId = new IslandId(UUID.randomUUID());
        ProfileId owner = ownerOf(islandId, service);

        assertThat(service.renameIsland(islandId, owner, WANTED).value()).isEqualTo(WANTED);
        assertThat(service.renameIsland(islandId, owner, WANTED).value()).isEqualTo(WANTED);
    }

    private int renameAllAtOnce(IslandNameService service) throws Exception {
        List<IslandId> islands = new ArrayList<>();
        for (int i = 0; i < CLAIMANTS; i++) {
            islands.add(new IslandId(UUID.randomUUID()));
        }
        Map<IslandId, ProfileId> owners = new HashMap<>();
        for (IslandId islandId : islands) {
            owners.put(islandId, ownerOf(islandId, service));
        }

        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CLAIMANTS);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (IslandId islandId : islands) {
                ProfileId owner = java.util.Objects.requireNonNull(owners.get(islandId));
                running.add(pool.submit(() -> {
                    try {
                        var unused = start.await(5, TimeUnit.SECONDS);
                        service.renameIsland(islandId, owner, WANTED);
                        accepted.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } catch (IllegalStateException refused) {
                        // The name was already taken, which is the whole point.
                    }
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return accepted.get();
    }

    /** Registers one island owned by a fresh profile and hands back that profile. */
    private ProfileId ownerOf(IslandId islandId, IslandNameService service) {
        ProfileId owner = new ProfileId(UUID.randomUUID());
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(owner.value()),
                owner,
                Instant.parse("2026-09-21T12:00:00Z"));
        islandsByIdFor(service).put(islandId, island);
        return owner;
    }

    private final Map<IslandNameService, Map<IslandId, Island>> registries = new ConcurrentHashMap<>();

    private Map<IslandId, Island> islandsByIdFor(IslandNameService service) {
        return java.util.Objects.requireNonNull(registries.get(service));
    }

    private IslandNameService serviceOver(IslandNameStoragePort storage) {
        Map<IslandId, Island> islands = new ConcurrentHashMap<>();
        IslandStoragePort islandStorage = mock(IslandStoragePort.class);
        when(islandStorage.findIslandById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(islands.get(invocation.<IslandId>getArgument(0))));
        IslandNameService service =
                new IslandNameService(storage, islandStorage, new IslandAccessService(), null, Set.of());
        registries.put(service, islands);
        return service;
    }
}
