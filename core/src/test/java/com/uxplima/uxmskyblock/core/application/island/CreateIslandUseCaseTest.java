package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.IslandCoordinates;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateIslandUseCaseTest {

    private FakeIslandStorage storage;
    private FakeIslandAuthority authority;
    private FakeIslandBank bank;
    private StarterPresetCatalog presetCatalog;
    private SpiralWorldGridService gridService;
    private FakeWorldGridAllocationPort allocationPort;
    private CreateIslandUseCase useCase;

    @BeforeEach
    void setUp() {
        storage = new FakeIslandStorage();
        authority = new FakeIslandAuthority();
        bank = new FakeIslandBank();
        presetCatalog = new StarterPresetCatalog();
        gridService = new SpiralWorldGridService();
        allocationPort = new FakeWorldGridAllocationPort();
        useCase = new CreateIslandUseCase(storage, authority, bank, presetCatalog, gridService, allocationPort);
    }

    @Test
    @DisplayName("successfully creates island when profile has no existing island and preset is valid")
    void createsIslandSuccessfully() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        CreateIslandUseCase.CreateIslandResult result =
                useCase.execute(playerUuid, profileId, "classic", ServerNodeId.of("node-1"), "world", 1L);

        assertThat(result).isInstanceOf(CreateIslandUseCase.CreateIslandResult.Success.class);
        CreateIslandUseCase.CreateIslandResult.Success success =
                (CreateIslandUseCase.CreateIslandResult.Success) result;

        assertThat(success.island().ownerPlayerUuid()).isEqualTo(playerUuid);
        assertThat(success.island().ownerProfileId()).isEqualTo(profileId);
        assertThat(success.location().worldName()).isEqualTo("world");
        assertThat(success.preset().id()).isEqualTo("classic");

        // Verify storage and authority were called
        assertThat(storage.islands).containsKey(success.island().id());
        assertThat(authority.acquired).containsKey(success.island().id());
        assertThat(bank.created).containsKey(success.island().id());
        assertThat(allocationPort.allocations).isNotEmpty();
    }

    @Test
    @DisplayName("successfully creates island using durable world grid allocation without sequence index")
    void createsIslandUsingDurableWorldGridAllocationWithoutSequenceIndex() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        CreateIslandUseCase.CreateIslandResult result =
                useCase.execute(playerUuid, profileId, "classic", ServerNodeId.of("node-1"), "world");

        assertThat(result).isInstanceOf(CreateIslandUseCase.CreateIslandResult.Success.class);
        CreateIslandUseCase.CreateIslandResult.Success success =
                (CreateIslandUseCase.CreateIslandResult.Success) result;

        assertThat(success.island().ownerPlayerUuid()).isEqualTo(playerUuid);
        assertThat(success.island().ownerProfileId()).isEqualTo(profileId);
        assertThat(success.location().worldName()).isEqualTo("world");
        assertThat(success.preset().id()).isEqualTo("classic");

        assertThat(storage.islands).containsKey(success.island().id());
        assertThat(authority.acquired).containsKey(success.island().id());
        assertThat(bank.created).containsKey(success.island().id());
        assertThat(allocationPort.allocations).isNotEmpty();
    }

    @Test
    @DisplayName("rejects creation when profile already owns an island")
    void rejectsWhenAlreadyHasIsland() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        CreateIslandUseCase.CreateIslandResult first =
                useCase.execute(playerUuid, profileId, "classic", ServerNodeId.of("node-1"), "world", 1L);
        assertThat(first).isInstanceOf(CreateIslandUseCase.CreateIslandResult.Success.class);

        CreateIslandUseCase.CreateIslandResult second =
                useCase.execute(playerUuid, profileId, "classic", ServerNodeId.of("node-1"), "world", 2L);
        assertThat(second).isInstanceOf(CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland.class);
    }

    @Test
    @DisplayName("rejects creation when preset is unknown")
    void rejectsWhenPresetIsUnknown() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        CreateIslandUseCase.CreateIslandResult result =
                useCase.execute(playerUuid, profileId, "invalid-preset", ServerNodeId.of("node-1"), "world", 1L);

        assertThat(result).isInstanceOf(CreateIslandUseCase.CreateIslandResult.UnknownPreset.class);
    }

    @Test
    @DisplayName("concurrent creation race condition falling back to existing island returns AlreadyHasIsland")
    void concurrentCreationFallbackReturnsAlreadyHasIsland() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());
        IslandId existingIslandId = IslandId.of(UUID.randomUUID());

        storage = new FakeIslandStorage() {
            @Override
            public void saveIsland(Island island, IslandLocation location) {
                profileToIsland.put(profileId, existingIslandId);
                throw new RuntimeException("UNIQUE constraint failed: islands.owner_profile_id");
            }
        };
        useCase = new CreateIslandUseCase(storage, authority, bank, presetCatalog, gridService, allocationPort);

        CreateIslandUseCase.CreateIslandResult result =
                useCase.execute(playerUuid, profileId, "classic", ServerNodeId.of("node-1"), "world");

        assertThat(result).isInstanceOf(CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland.class);
        CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland already =
                (CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) result;
        assertThat(already.existingIslandId()).isEqualTo(existingIslandId);
    }

    private static class FakeIslandStorage implements IslandStoragePort {
        final Map<IslandId, Island> islands = new ConcurrentHashMap<>();
        final Map<ProfileId, IslandId> profileToIsland = new ConcurrentHashMap<>();
        final Map<IslandId, IslandLocation> locations = new ConcurrentHashMap<>();

        /**
         * Holds every lookup until they have all happened, which is the interleaving a double click
         * produces and a spin on a quiet machine does not.
         */
        @Nullable CountDownLatch everybodyLooked;

        @Override
        public void saveIsland(Island island, IslandLocation location) {
            islands.put(island.id(), island);
            profileToIsland.put(island.ownerProfileId(), island.id());
            locations.put(island.id(), location);
        }

        @Override
        public Optional<Island> findIslandById(IslandId islandId) {
            return Optional.ofNullable(islands.get(islandId));
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            Optional<IslandId> held = Optional.ofNullable(profileToIsland.get(profileId));
            CountDownLatch latch = everybodyLooked;
            if (latch != null) {
                latch.countDown();
                try {
                    var unused = latch.await(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return held;
        }

        @Override
        public Optional<IslandLocation> findLocationByIslandId(IslandId islandId) {
            return Optional.ofNullable(locations.get(islandId));
        }

        @Override
        public void deleteIsland(IslandId islandId) {
            islands.remove(islandId);
            locations.remove(islandId);
        }
    }

    @Test
    @DisplayName("A new island reaches as far as it is told, not as far as a number in the code said")
    void theStartingRadiusIsTheOneItIsGiven() {
        CreateIslandUseCase sized = new CreateIslandUseCase(
                storage, authority, bank, presetCatalog, gridService, allocationPort, null, null, ignored -> 37, 64);

        CreateIslandUseCase.CreateIslandResult result = sized.execute(
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                "classic",
                ServerNodeId.of("node-1"),
                "world");

        CreateIslandUseCase.CreateIslandResult.Success success =
                (CreateIslandUseCase.CreateIslandResult.Success) result;
        assertThat(success.island().bounds().radius())
                .describedAs("how far the new island reaches")
                .isEqualTo(37);
        assertThat(success.location().spawnY())
                .describedAs("how high the new island's spawn sits")
                .isEqualTo(65.0);
    }

    @Test
    @DisplayName("A profile that clicks create eight times gets one island, not eight")
    void eightClicksMakeOneIsland() throws Exception {
        int clicks = 8;
        storage.everybodyLooked = new CountDownLatch(clicks);
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        AtomicInteger created = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clicks);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int i = 0; i < clicks; i++) {
                running.add(pool.submit(() -> {
                    try {
                        var unused = start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    CreateIslandUseCase.CreateIslandResult result =
                            useCase.execute(playerUuid, profileId, "classic", ServerNodeId.of("node-1"), "world");
                    if (result instanceof CreateIslandUseCase.CreateIslandResult.Success) {
                        created.incrementAndGet();
                    } else if (result instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                        refused.incrementAndGet();
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

        assertThat(created.get()).describedAs("islands built").isEqualTo(1);
        assertThat(refused.get())
                .describedAs("clicks told they already have one")
                .isEqualTo(clicks - 1);
        assertThat(storage.islands).describedAs("islands in storage").hasSize(1);
        assertThat(bank.created).describedAs("banks opened").hasSize(1);
        assertThat(allocationPort.allocations)
                .describedAs("plots taken out of the world grid")
                .hasSize(1);
    }

    private static class FakeIslandAuthority implements IslandAuthorityPort {
        final Map<IslandId, ServerNodeId> acquired = new HashMap<>();

        @Override
        public IslandAuthorityOutcome acquireAuthority(IslandId islandId, ServerNodeId nodeId, int leaseSeconds) {
            acquired.put(islandId, nodeId);
            return new IslandAuthorityOutcome.Success(1L);
        }

        @Override
        public IslandAuthorityOutcome renewAuthority(
                IslandId islandId, ServerNodeId nodeId, long expectedEpoch, int leaseSeconds) {
            return new IslandAuthorityOutcome.Success(expectedEpoch);
        }

        @Override
        public IslandAuthorityOutcome takeoverAuthority(
                IslandId islandId, ServerNodeId newNodeId, long expectedEpoch, int leaseSeconds) {
            acquired.put(islandId, newNodeId);
            return new IslandAuthorityOutcome.Success(expectedEpoch + 1);
        }

        @Override
        public Optional<IslandAuthorityRecord> findAuthority(IslandId islandId) {
            return Optional.empty();
        }

        @Override
        public com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep sweepAuthority(
                ServerNodeId nodeId, String worldName, int leaseSeconds) {
            return com.uxplima.uxmskyblock.core.domain.island.IslandAuthoritySweep.NOTHING;
        }
    }

    private static class FakeIslandBank implements IslandBankPort {
        final Map<IslandId, IslandBank> created = new HashMap<>();

        @Override
        public Optional<IslandBank> findBankByIslandId(IslandId islandId) {
            return Optional.ofNullable(created.get(islandId));
        }

        @Override
        public IslandBank createBank(IslandId islandId) {
            IslandBank bank = IslandBank.initial(islandId);
            created.put(islandId, bank);
            return bank;
        }

        @Override
        public BankTransactionOutcome executeTransaction(
                IslandId islandId,
                UUID actorUuid,
                String currencyId,
                int currencyScale,
                long deltaAmountMinorUnits,
                String reason,
                String currentNode,
                long expectedEpoch,
                long expectedVersion,
                UUID operationId,
                String idempotencyKey) {
            IslandBank bank = new IslandBank(
                    islandId, deltaAmountMinorUnits, 0L, 0L, expectedVersion + 1, java.time.Instant.now());
            BankTransaction tx = new BankTransaction(
                    UUID.randomUUID(),
                    operationId,
                    islandId,
                    actorUuid,
                    currencyId,
                    currencyScale,
                    deltaAmountMinorUnits,
                    deltaAmountMinorUnits,
                    reason,
                    java.time.Instant.now());
            return new BankTransactionOutcome.Success(bank, tx);
        }

        @Override
        public List<BankTransaction> getTransactionHistory(IslandId islandId, int limit) {
            return List.of();
        }
    }

    private static class FakeWorldGridAllocationPort implements WorldGridAllocationPort {
        private final AtomicLong seqCounter = new AtomicLong(0);
        private final SpiralGridCoordinateAllocator allocator = new SpiralGridCoordinateAllocator();
        final Map<Long, WorldGridAllocation> allocations = new HashMap<>();

        @Override
        public long reserveNextSequence(
                ServerNodeId nodeId, String worldName, int centerX, int centerZ, @Nullable IslandId islandId) {
            long seq = seqCounter.getAndIncrement();
            allocations.put(
                    seq,
                    new WorldGridAllocation(
                            seq,
                            worldName,
                            centerX,
                            centerZ,
                            Optional.ofNullable(islandId),
                            nodeId,
                            java.time.Instant.now()));
            return seq;
        }

        @Override
        public WorldGridAllocation allocateNext(ServerNodeId nodeId, String worldName, @Nullable IslandId islandId) {
            long seq = seqCounter.getAndIncrement();
            IslandCoordinates coords = allocator.coordinatesForIndex(seq);
            WorldGridAllocation alloc = new WorldGridAllocation(
                    seq,
                    worldName,
                    coords.x(),
                    coords.z(),
                    Optional.ofNullable(islandId),
                    nodeId,
                    java.time.Instant.now());
            allocations.put(seq, alloc);
            return alloc;
        }

        @Override
        public Optional<WorldGridAllocation> findBySequenceIndex(long sequenceIndex) {
            return Optional.ofNullable(allocations.get(sequenceIndex));
        }

        @Override
        public Optional<WorldGridAllocation> findByIslandId(IslandId islandId) {
            return allocations.values().stream()
                    .filter(a -> a.islandId().equals(Optional.of(islandId)))
                    .findFirst();
        }

        @Override
        public Optional<WorldGridAllocation> findByCoordinates(String worldName, int centerX, int centerZ) {
            return allocations.values().stream()
                    .filter(a -> a.worldName().equals(worldName) && a.centerX() == centerX && a.centerZ() == centerZ)
                    .findFirst();
        }

        @Override
        public Optional<Long> findMaxSequenceIndex(String worldName) {
            return allocations.values().stream()
                    .filter(a -> a.worldName().equals(worldName))
                    .map(WorldGridAllocation::sequenceIndex)
                    .max(Long::compareTo);
        }

        @Override
        public void bindIsland(long sequenceIndex, IslandId islandId) {
            WorldGridAllocation existing = allocations.get(sequenceIndex);
            if (existing != null) {
                allocations.put(
                        sequenceIndex,
                        new WorldGridAllocation(
                                existing.sequenceIndex(),
                                existing.worldName(),
                                existing.centerX(),
                                existing.centerZ(),
                                Optional.of(islandId),
                                existing.allocatedByNode(),
                                existing.allocatedAt()));
            }
        }
    }
}
