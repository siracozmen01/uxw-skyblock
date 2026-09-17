package com.uxplima.uxmskyblock.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@SuppressWarnings("NullAway")
class UxmSkyblockApiTest {

    @AfterEach
    void tearDown() {
        UxmSkyblockApiProvider.unregister();
    }

    @Test
    @DisplayName("IslandSnapshot records validate non-null arguments")
    void islandSnapshotValidates() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Instant now = Instant.now();

        IslandSnapshot snapshot = new IslandSnapshot(id, owner, -50, -50, 50, 50, now);
        assertThat(snapshot.islandId()).isEqualTo(id);
        assertThat(snapshot.ownerUuid()).isEqualTo(owner);
        assertThat(snapshot.minX()).isEqualTo(-50);
        assertThat(snapshot.createdAt()).isEqualTo(now);

        assertThatThrownBy(() -> new IslandSnapshot(null, owner, 0, 0, 10, 10, now))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("MemberSnapshot records validate non-null arguments")
    void memberSnapshotValidates() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();

        MemberSnapshot member = new MemberSnapshot(player, "MEMBER", now);
        assertThat(member.playerUuid()).isEqualTo(player);
        assertThat(member.role()).isEqualTo("MEMBER");
        assertThat(member.joinedAt()).isEqualTo(now);

        assertThatThrownBy(() -> new MemberSnapshot(null, "MEMBER", now)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("IslandLeaderboardEntry records validate ranks and non-null arguments")
    void leaderboardEntryValidates() {
        UUID id = UUID.randomUUID();
        IslandLeaderboardEntry entry = new IslandLeaderboardEntry(1, id, "TopIsland", 1500, "1,500 pts");

        assertThat(entry.rank()).isEqualTo(1);
        assertThat(entry.islandName()).isEqualTo("TopIsland");
        assertThat(entry.score()).isEqualTo(1500);

        assertThatThrownBy(() -> new IslandLeaderboardEntry(0, id, "TopIsland", 1500, "1,500 pts"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("IslandBankBalance converts minor units to decimal correctly")
    void bankBalanceComputes() {
        UUID id = UUID.randomUUID();
        IslandBankBalance balance = new IslandBankBalance(id, 12550);

        assertThat(balance.balanceMinorUnits()).isEqualTo(12550);
        assertThat(balance.balance()).isEqualTo(125.50);
    }

    @Test
    @DisplayName("IslandResult handles sealed variants cleanly")
    void islandResultVariants() {
        IslandResult<String> success = IslandResult.success("ok");
        assertThat(success).isInstanceOf(IslandResult.Success.class);

        IslandResult<String> failure = IslandResult.failure("error");
        assertThat(failure).isInstanceOf(IslandResult.Failure.class);

        IslandResult<String> unavailable = IslandResult.unavailable("feature.warp");
        assertThat(unavailable).isInstanceOf(IslandResult.FeatureUnavailable.class);
    }

    @Test
    @DisplayName("UxmSkyblockApiProvider manages global singleton lifecycle")
    void apiProviderLifecycle() {
        assertThat(UxmSkyblockApiProvider.isRegistered()).isFalse();
        assertThatThrownBy(UxmSkyblockApiProvider::get).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(UxmSkyblockApi::getInstance).isInstanceOf(IllegalStateException.class);

        UxmSkyblockQuery mockQuery = new UxmSkyblockQuery() {
            @Override
            public CompletableFuture<Optional<IslandSnapshot>> getIsland(UUID islandId) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<IslandSnapshot>> getPlayerIsland(UUID playerId) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<List<IslandLeaderboardEntry>> getTopIslands(int limit) {
                return CompletableFuture.completedFuture(List.of());
            }

            @Override
            public CompletableFuture<Optional<IslandBankBalance>> getBankBalance(UUID islandId) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };

        UxmSkyblockActions mockActions = new UxmSkyblockActions() {
            @Override
            public CompletableFuture<IslandResult<IslandSnapshot>> createIsland(UUID ownerId, String presetId) {
                return CompletableFuture.completedFuture(IslandResult.unavailable("disabled"));
            }

            @Override
            public CompletableFuture<IslandResult<IslandBankBalance>> depositBank(
                    UUID islandId, UUID actorId, long amountMinorUnits) {
                return CompletableFuture.completedFuture(IslandResult.unavailable("disabled"));
            }

            @Override
            public CompletableFuture<IslandResult<IslandBankBalance>> withdrawBank(
                    UUID islandId, UUID actorId, long amountMinorUnits) {
                return CompletableFuture.completedFuture(IslandResult.unavailable("disabled"));
            }
        };

        UxmSkyblockApi mockApi = new UxmSkyblockApi() {
            @Override
            public UxmSkyblockQuery query() {
                return mockQuery;
            }

            @Override
            public UxmSkyblockActions actions() {
                return mockActions;
            }
        };

        UxmSkyblockApiProvider.register(mockApi);
        assertThat(UxmSkyblockApiProvider.isRegistered()).isTrue();
        assertThat(UxmSkyblockApiProvider.get()).isSameAs(mockApi);
        assertThat(UxmSkyblockApi.getInstance()).isSameAs(mockApi);

        UxmSkyblockApiProvider.unregister();
        assertThat(UxmSkyblockApiProvider.isRegistered()).isFalse();
    }
}
