package com.uxplima.uxmskyblock.bukkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.api.IslandBankBalance;
import com.uxplima.uxmskyblock.api.IslandLeaderboardEntry;
import com.uxplima.uxmskyblock.api.IslandResult;
import com.uxplima.uxmskyblock.api.IslandSnapshot;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BukkitSkyblockApiBridgeTest {

    private IslandStoragePort storagePort;
    private IslandBankPort bankPort;
    private IslandLeaderboardPort leaderboardPort;
    private IslandBankService bankService;
    private CreateIslandUseCase createIslandUseCase;
    private ServerNodeId nodeId;
    private DirectScheduler scheduler;
    private BukkitSkyblockApiBridge apiBridge;

    private UUID playerUuid;
    private ProfileId profileId;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        storagePort = mock(IslandStoragePort.class);
        bankPort = mock(IslandBankPort.class);
        leaderboardPort = mock(IslandLeaderboardPort.class);
        bankService = mock(IslandBankService.class);
        createIslandUseCase = mock(CreateIslandUseCase.class);
        nodeId = ServerNodeId.of("node-1");
        scheduler = new DirectScheduler();

        playerUuid = UUID.randomUUID();
        profileId = new ProfileId(playerUuid);
        islandId = IslandId.of(UUID.randomUUID());

        apiBridge = new BukkitSkyblockApiBridge(
                storagePort,
                bankPort,
                leaderboardPort,
                bankService,
                createIslandUseCase,
                nodeId,
                scheduler,
                uuid -> Optional.of(profileId),
                "skyblock_world");
    }

    @Test
    @DisplayName("getIsland retrieves island snapshot asynchronously via storage port")
    void getIslandRetrievesSnapshot() {
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(playerUuid),
                profileId,
                Instant.now());
        when(storagePort.findIslandById(islandId)).thenReturn(Optional.of(island));

        Optional<IslandSnapshot> snapshot =
                apiBridge.query().getIsland(islandId.value()).join();

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().islandId()).isEqualTo(islandId.value());
        assertThat(snapshot.get().ownerUuid()).isEqualTo(playerUuid);
    }

    @Test
    @DisplayName("getPlayerIsland resolves profile and returns snapshot")
    void getPlayerIslandResolvesSnapshot() {
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(playerUuid),
                profileId,
                Instant.now());
        when(storagePort.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        when(storagePort.findIslandById(islandId)).thenReturn(Optional.of(island));

        Optional<IslandSnapshot> snapshot =
                apiBridge.query().getPlayerIsland(playerUuid).join();

        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().islandId()).isEqualTo(islandId.value());
    }

    @Test
    @DisplayName("getTopIslands fetches mapped leaderboard entries")
    void getTopIslandsReturnsMappedEntries() {
        LeaderboardEntry entry = new LeaderboardEntry(1, islandId, "Island 1", 500L, "500");
        when(leaderboardPort.fetchTopIslands(LeaderboardCategory.LEVEL, 10)).thenReturn(List.of(entry));

        List<IslandLeaderboardEntry> list = apiBridge.query().getTopIslands(10).join();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).islandId()).isEqualTo(islandId.value());
        assertThat(list.get(0).islandName()).isEqualTo("Island 1");
    }

    @Test
    @DisplayName("getBankBalance fetches balance from bank port")
    void getBankBalanceReturnsBalance() {
        IslandBank bank = new IslandBank(islandId, 25000L, 0L, 0L, 1L, Instant.now());
        when(bankPort.findBankByIslandId(islandId)).thenReturn(Optional.of(bank));

        Optional<IslandBankBalance> bal =
                apiBridge.query().getBankBalance(islandId.value()).join();

        assertThat(bal).isPresent();
        assertThat(bal.get().balanceMinorUnits()).isEqualTo(25000L);
    }

    @Test
    @DisplayName("createIsland delegates to CreateIslandUseCase and returns snapshot on success")
    void createIslandDelegatesToUseCase() {
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(100, 100, 50),
                new PlayerUuid(playerUuid),
                profileId,
                Instant.now());
        IslandLocation location =
                new IslandLocation(islandId, "skyblock_world", island.bounds(), 100.5, 100.0, 100.5, 0.0f, 0.0f);
        StarterPreset preset = new StarterPreset(
                "classic",
                "Classic Island",
                "A classic skyblock island",
                "schematics/classic.schem",
                com.uxplima.uxmskyblock.core.domain.biome.IslandBiome.PLAINS);
        when(createIslandUseCase.execute(
                        eq(new PlayerUuid(playerUuid)), eq(profileId), eq("classic"), eq(nodeId), eq("skyblock_world")))
                .thenReturn(new CreateIslandUseCase.CreateIslandResult.Success(island, location, preset));

        IslandResult<IslandSnapshot> result =
                apiBridge.actions().createIsland(playerUuid, "classic").join();

        assertThat(result).isInstanceOf(IslandResult.Success.class);
        IslandResult.Success<IslandSnapshot> success = (IslandResult.Success<IslandSnapshot>) result;
        assertThat(success.value().islandId()).isEqualTo(islandId.value());
    }

    @Test
    @DisplayName("depositBank delegates to IslandBankService.depositToIsland")
    void depositBankDelegatesToService() {
        IslandBank updatedBank = new IslandBank(islandId, 75000L, 0L, 0L, 2L, Instant.now());
        BankTransaction tx = new BankTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                islandId,
                playerUuid,
                "PRIMARY",
                2,
                50000L,
                75000L,
                "API deposit",
                Instant.now());
        when(bankService.depositToIsland(eq(islandId), eq(new PlayerUuid(playerUuid)), eq(50000L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.Success(updatedBank, tx));

        IslandResult<IslandBankBalance> result = apiBridge
                .actions()
                .depositBank(islandId.value(), playerUuid, 50000L)
                .join();

        assertThat(result).isInstanceOf(IslandResult.Success.class);
        IslandResult.Success<IslandBankBalance> success = (IslandResult.Success<IslandBankBalance>) result;
        assertThat(success.value().balanceMinorUnits()).isEqualTo(75000L);
    }

    @Test
    @DisplayName("withdrawBank returns failure when IslandBankService rejects authority")
    void withdrawBankHandlesAuthorityRejection() {
        when(bankService.withdrawFromIsland(eq(islandId), eq(new PlayerUuid(playerUuid)), eq(50000L), eq(nodeId)))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("Node mismatch"));

        IslandResult<IslandBankBalance> result = apiBridge
                .actions()
                .withdrawBank(islandId.value(), playerUuid, 50000L)
                .join();

        assertThat(result).isInstanceOf(IslandResult.Failure.class);
        IslandResult.Failure<IslandBankBalance> failure = (IslandResult.Failure<IslandBankBalance>) result;
        assertThat(failure.reason()).contains("Authority rejected: Node mismatch");
    }

    private static class DirectScheduler implements SchedulerPort {
        @Override
        public void onGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
            task.run();
        }

        @Override
        public void onEntity(PlayerUuid playerUuid, Runnable task) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            task.run();
        }

        @Override
        public void asyncAfter(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public void laterGlobal(Duration delay, Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }

        @Override
        public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
            return () -> {};
        }
    }
}
