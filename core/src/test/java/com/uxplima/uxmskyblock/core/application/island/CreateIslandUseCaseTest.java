package com.uxplima.uxmskyblock.core.application.island;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.world.SpiralWorldGridService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateIslandUseCaseTest {

    private FakeIslandStorage storage;
    private FakeIslandAuthority authority;
    private FakeIslandBank bank;
    private StarterPresetCatalog presetCatalog;
    private SpiralWorldGridService gridService;
    private CreateIslandUseCase useCase;

    @BeforeEach
    void setUp() {
        storage = new FakeIslandStorage();
        authority = new FakeIslandAuthority();
        bank = new FakeIslandBank();
        presetCatalog = new StarterPresetCatalog();
        gridService = new SpiralWorldGridService();
        useCase = new CreateIslandUseCase(storage, authority, bank, presetCatalog, gridService);
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

    private static class FakeIslandStorage implements IslandStoragePort {
        final Map<IslandId, Island> islands = new HashMap<>();
        final Map<ProfileId, IslandId> profileToIsland = new HashMap<>();
        final Map<IslandId, IslandLocation> locations = new HashMap<>();

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
            return Optional.ofNullable(profileToIsland.get(profileId));
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
}
