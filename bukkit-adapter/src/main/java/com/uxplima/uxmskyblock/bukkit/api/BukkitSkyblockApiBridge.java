package com.uxplima.uxmskyblock.bukkit.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

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
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
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
    private final IslandBankService islandBankService;
    private final CreateIslandUseCase createIslandUseCase;
    private final ServerNodeId serverNodeId;
    private final SchedulerPort schedulerPort;
    private final Function<UUID, Optional<ProfileId>> activeProfileProvider;
    private final String defaultWorldName;

    public BukkitSkyblockApiBridge(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandLeaderboardPort islandLeaderboardPort,
            IslandBankService islandBankService,
            CreateIslandUseCase createIslandUseCase,
            ServerNodeId serverNodeId,
            SchedulerPort schedulerPort,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            String defaultWorldName) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort");
        this.islandLeaderboardPort = Objects.requireNonNull(islandLeaderboardPort, "islandLeaderboardPort");
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService");
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.activeProfileProvider = Objects.requireNonNull(activeProfileProvider, "activeProfileProvider");
        this.defaultWorldName = Objects.requireNonNull(defaultWorldName, "defaultWorldName");
    }

    public BukkitSkyblockApiBridge(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandLeaderboardPort islandLeaderboardPort,
            IslandBankService islandBankService,
            CreateIslandUseCase createIslandUseCase,
            ServerNodeId serverNodeId,
            SchedulerPort schedulerPort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            String defaultWorldName) {
        this(
                islandStoragePort,
                islandBankPort,
                islandLeaderboardPort,
                islandBankService,
                createIslandUseCase,
                serverNodeId,
                schedulerPort,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty(),
                defaultWorldName);
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
        CompletableFuture<Optional<IslandSnapshot>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                future.complete(islandStoragePort.findIslandById(IslandId.of(islandId)).map(this::toSnapshot));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Optional<IslandSnapshot>> getPlayerIsland(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        CompletableFuture<Optional<IslandSnapshot>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                Optional<ProfileId> optProfile = activeProfileProvider.apply(playerId);
                if (optProfile.isEmpty()) {
                    future.complete(Optional.empty());
                    return;
                }
                ProfileId profileId = optProfile.get();
                Optional<IslandSnapshot> snapshot = islandStoragePort
                        .findIslandIdByProfileId(profileId)
                        .flatMap(islandStoragePort::findIslandById)
                        .map(this::toSnapshot);
                future.complete(snapshot);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<List<IslandLeaderboardEntry>> getTopIslands(int limit) {
        CompletableFuture<List<IslandLeaderboardEntry>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                List<IslandLeaderboardEntry> entries = islandLeaderboardPort.fetchTopIslands(LeaderboardCategory.LEVEL, limit).stream()
                        .map(entry -> new IslandLeaderboardEntry(
                                entry.rank(),
                                entry.islandId().value(),
                                entry.islandName(),
                                entry.score(),
                                entry.formattedScore()))
                        .toList();
                future.complete(entries);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Optional<IslandBankBalance>> getBankBalance(UUID islandId) {
        Objects.requireNonNull(islandId, "islandId");
        CompletableFuture<Optional<IslandBankBalance>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                Optional<IslandBankBalance> balance = islandBankPort
                        .findBankByIslandId(IslandId.of(islandId))
                        .map(bank -> new IslandBankBalance(islandId, bank.primaryBalanceMinorUnits()));
                future.complete(balance);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<IslandResult<IslandSnapshot>> createIsland(UUID ownerId, String presetId) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(presetId, "presetId");
        CompletableFuture<IslandResult<IslandSnapshot>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                Optional<ProfileId> optProfile = activeProfileProvider.apply(ownerId);
                if (optProfile.isEmpty()) {
                    future.complete(IslandResult.failure("Active profile not found or session not loaded for player: " + ownerId));
                    return;
                }
                ProfileId profileId = optProfile.get();
                PlayerUuid playerUuid = new PlayerUuid(ownerId);

                CreateIslandUseCase.CreateIslandResult result =
                        createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, defaultWorldName);
                if (result instanceof CreateIslandUseCase.CreateIslandResult.Success succ) {
                    future.complete(IslandResult.success(toSnapshot(succ.island())));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    future.complete(IslandResult.failure("Player already belongs to an island"));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unk) {
                    future.complete(IslandResult.failure("Unknown preset '" + unk.presetId() + "'"));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.Failure fail) {
                    future.complete(IslandResult.failure("Failed to create island: " + fail.reason()));
                } else {
                    future.complete(IslandResult.failure("Unexpected island creation outcome"));
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<IslandResult<IslandBankBalance>> depositBank(
            UUID islandId, UUID actorId, long amountMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorId, "actorId");
        CompletableFuture<IslandResult<IslandBankBalance>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                BankTransactionOutcome outcome = islandBankService.depositToIsland(
                        IslandId.of(islandId),
                        new PlayerUuid(actorId),
                        amountMinorUnits,
                        serverNodeId);

                if (outcome instanceof BankTransactionOutcome.Success success) {
                    future.complete(IslandResult.success(
                            new IslandBankBalance(islandId, success.updatedBank().primaryBalanceMinorUnits())));
                } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rejected) {
                    future.complete(IslandResult.failure("Authority rejected: " + rejected.reason()));
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    future.complete(IslandResult.failure("Insufficient funds"));
                } else {
                    future.complete(IslandResult.failure("Bank deposit failed: " + outcome));
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<IslandResult<IslandBankBalance>> withdrawBank(
            UUID islandId, UUID actorId, long amountMinorUnits) {
        Objects.requireNonNull(islandId, "islandId");
        Objects.requireNonNull(actorId, "actorId");
        CompletableFuture<IslandResult<IslandBankBalance>> future = new CompletableFuture<>();
        schedulerPort.async(() -> {
            try {
                BankTransactionOutcome outcome = islandBankService.withdrawFromIsland(
                        IslandId.of(islandId),
                        new PlayerUuid(actorId),
                        amountMinorUnits,
                        serverNodeId);

                if (outcome instanceof BankTransactionOutcome.Success success) {
                    future.complete(IslandResult.success(
                            new IslandBankBalance(islandId, success.updatedBank().primaryBalanceMinorUnits())));
                } else if (outcome instanceof BankTransactionOutcome.AuthorityRejected rejected) {
                    future.complete(IslandResult.failure("Authority rejected: " + rejected.reason()));
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    future.complete(IslandResult.failure("Insufficient funds"));
                } else {
                    future.complete(IslandResult.failure("Bank withdrawal failed: " + outcome));
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
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
