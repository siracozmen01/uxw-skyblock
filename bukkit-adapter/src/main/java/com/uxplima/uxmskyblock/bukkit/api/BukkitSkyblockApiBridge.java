package com.uxplima.uxmskyblock.bukkit.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.uxplima.uxmskyblock.api.IslandBankBalance;
import com.uxplima.uxmskyblock.api.IslandLeaderboardEntry;
import com.uxplima.uxmskyblock.api.IslandResult;
import com.uxplima.uxmskyblock.api.IslandSnapshot;
import com.uxplima.uxmskyblock.api.UxmSkyblockActions;
import com.uxplima.uxmskyblock.api.UxmSkyblockApi;
import com.uxplima.uxmskyblock.api.UxmSkyblockApiProvider;
import com.uxplima.uxmskyblock.api.UxmSkyblockQuery;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Concrete implementation bridging public API calls from (:api) to internal domain ports.
 */
public final class BukkitSkyblockApiBridge implements UxmSkyblockApi, UxmSkyblockQuery, UxmSkyblockActions {

    private final IslandStoragePort islandStoragePort;
    private final IslandBankPort islandBankPort;
    private final IslandLeaderboardPort islandLeaderboardPort;
    private final IslandAuthorityPort islandAuthorityPort;
    private final ServerNodeId serverNodeId;
    private final @Nullable CreateIslandUseCase createIslandUseCase;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final String defaultWorldName;

    public BukkitSkyblockApiBridge(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandLeaderboardPort islandLeaderboardPort,
            IslandAuthorityPort islandAuthorityPort,
            ServerNodeId serverNodeId,
            @Nullable CreateIslandUseCase createIslandUseCase,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            String defaultWorldName) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort");
        this.islandLeaderboardPort = Objects.requireNonNull(islandLeaderboardPort, "islandLeaderboardPort");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId");
        this.createIslandUseCase = createIslandUseCase;
        this.sessionCoordinator = sessionCoordinator;
        this.defaultWorldName = Objects.requireNonNull(defaultWorldName, "defaultWorldName");
    }

    public BukkitSkyblockApiBridge(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandLeaderboardPort islandLeaderboardPort,
            IslandAuthorityPort islandAuthorityPort,
            ServerNodeId serverNodeId) {
        this(
                islandStoragePort,
                islandBankPort,
                islandLeaderboardPort,
                islandAuthorityPort,
                serverNodeId,
                null,
                null,
                "skyblock_world");
    }

    public BukkitSkyblockApiBridge(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandLeaderboardPort islandLeaderboardPort,
            IslandAuthorityPort islandAuthorityPort) {
        this(
                islandStoragePort,
                islandBankPort,
                islandLeaderboardPort,
                islandAuthorityPort,
                ServerNodeId.of("skyblock-node-default"));
    }

    public void register() {
        UxmSkyblockApiProvider.register(this);
    }

    public void unregister() {
        UxmSkyblockApiProvider.unregister();
    }

    @Override
    public UxmSkyblockQuery query() {
        return this;
    }

    @Override
    public UxmSkyblockActions actions() {
        return this;
    }

    @Override
    public CompletableFuture<Optional<IslandSnapshot>> getIsland(UUID islandId) {
        Objects.requireNonNull(islandId, "islandId");
        return CompletableFuture.supplyAsync(
                () -> islandStoragePort.findIslandById(IslandId.of(islandId)).map(this::toSnapshot));
    }

    @Override
    public CompletableFuture<Optional<IslandSnapshot>> getPlayerIsland(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return CompletableFuture.supplyAsync(() -> {
            if (sessionCoordinator == null) {
                return Optional.empty();
            }
            Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(playerId);
            if (optProfile.isEmpty()) {
                return Optional.empty();
            }
            ProfileId profileId = optProfile.get();
            return islandStoragePort
                    .findIslandIdByProfileId(profileId)
                    .flatMap(islandStoragePort::findIslandById)
                    .map(this::toSnapshot);
        });
    }

    @Override
    public CompletableFuture<List<IslandLeaderboardEntry>> getTopIslands(int limit) {
        return CompletableFuture.supplyAsync(
                () -> islandLeaderboardPort.fetchTopIslands(LeaderboardCategory.LEVEL, limit).stream()
                        .map(entry -> new IslandLeaderboardEntry(
                                entry.rank(),
                                entry.islandId().value(),
                                entry.islandName(),
                                entry.score(),
                                entry.formattedScore()))
                        .toList());
    }

    @Override
    public CompletableFuture<Optional<IslandBankBalance>> getBankBalance(UUID islandId) {
        Objects.requireNonNull(islandId, "islandId");
        return CompletableFuture.supplyAsync(() -> islandBankPort
                .findBankByIslandId(IslandId.of(islandId))
                .map(bank -> new IslandBankBalance(islandId, bank.primaryBalanceMinorUnits())));
    }

    @Override
    public CompletableFuture<IslandResult<IslandSnapshot>> createIsland(UUID ownerId, String presetId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(presetId, "presetId");
        return CompletableFuture.supplyAsync(() -> {
            if (sessionCoordinator == null) {
                return IslandResult.failure("Session coordinator is unavailable");
            }
            Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(ownerId);
            if (optProfile.isEmpty()) {
                return IslandResult.failure("Active profile not found or session not loaded for player: " + ownerId);
            }
            ProfileId profileId = optProfile.get();
            PlayerUuid playerUuid = new PlayerUuid(ownerId);

            if (createIslandUseCase != null) {
                CreateIslandUseCase.CreateIslandResult result =
                        createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, defaultWorldName);
                if (result instanceof CreateIslandUseCase.CreateIslandResult.Success succ) {
                    return IslandResult.success(toSnapshot(succ.island()));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    return IslandResult.failure("Player already belongs to an island");
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unk) {
                    return IslandResult.failure("Unknown preset '" + unk.presetId() + "'");
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.Failure fail) {
                    return IslandResult.failure("Failed to create island: " + fail.reason());
                }
            }

            if (islandStoragePort.findIslandIdByProfileId(profileId).isPresent()) {
                return IslandResult.failure("Player already belongs to an island");
            }

            IslandId islandId = IslandId.of(UUID.randomUUID());
            IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
            Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());
            IslandLocation location =
                    new IslandLocation(islandId, defaultWorldName, bounds, 0.5, 100.0, 0.5, 0.0f, 0.0f);

            islandStoragePort.saveIsland(island, location);
            islandAuthorityPort.acquireAuthority(islandId, serverNodeId, 86400);
            islandBankPort.createBank(islandId);

            return IslandResult.success(toSnapshot(island));
        });
    }

    @Override
    public CompletableFuture<IslandResult<IslandBankBalance>> depositBank(
            UUID islandId, UUID actorId, long amountMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorId, "actorId");
        return CompletableFuture.supplyAsync(() -> {
            IslandId id = IslandId.of(islandId);
            Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(id);
            IslandBank bank = optBank.orElseGet(() -> islandBankPort.createBank(id));

            UUID opId = UUID.randomUUID();
            BankTransactionOutcome outcome = islandBankPort.executeTransaction(
                    id,
                    actorId,
                    "PRIMARY",
                    2,
                    amountMinorUnits,
                    "API deposit",
                    serverNodeId.value(),
                    1L,
                    bank.version(),
                    opId,
                    "api-dep-" + opId);

            if (outcome instanceof BankTransactionOutcome.Success success) {
                return IslandResult.success(
                        new IslandBankBalance(islandId, success.updatedBank().primaryBalanceMinorUnits()));
            }
            return IslandResult.failure("Bank deposit failed: " + outcome);
        });
    }

    @Override
    public CompletableFuture<IslandResult<IslandBankBalance>> withdrawBank(
            UUID islandId, UUID actorId, long amountMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorId, "actorId");
        return CompletableFuture.supplyAsync(() -> {
            IslandId id = IslandId.of(islandId);
            Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(id);
            if (optBank.isEmpty()) {
                return IslandResult.failure("Bank not found");
            }
            IslandBank bank = optBank.get();

            UUID opId = UUID.randomUUID();
            BankTransactionOutcome outcome = islandBankPort.executeTransaction(
                    id,
                    actorId,
                    "PRIMARY",
                    2,
                    -amountMinorUnits,
                    "API withdrawal",
                    serverNodeId.value(),
                    1L,
                    bank.version(),
                    opId,
                    "api-wth-" + opId);

            if (outcome instanceof BankTransactionOutcome.Success success) {
                return IslandResult.success(
                        new IslandBankBalance(islandId, success.updatedBank().primaryBalanceMinorUnits()));
            } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                return IslandResult.failure("Insufficient funds");
            }
            return IslandResult.failure("Bank withdrawal failed: " + outcome);
        });
    }

    private IslandSnapshot toSnapshot(Island island) {
        return new IslandSnapshot(
                island.id().value(),
                island.ownerPlayerUuid().value(),
                island.bounds().minX(),
                island.bounds().minZ(),
                island.bounds().maxX(),
                island.bounds().maxZ(),
                island.createdAt());
    }
}
