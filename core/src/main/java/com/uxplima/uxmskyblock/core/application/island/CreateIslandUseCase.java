package com.uxplima.uxmskyblock.core.application.island;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.gamemode.GameModeHierarchyService;
import com.uxplima.uxmskyblock.core.application.lock.KeyedMutationLock;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.JsonText;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.IslandCoordinates;
import com.uxplima.uxmskyblock.core.domain.world.WorldGridAllocation;
import org.jspecify.annotations.Nullable;

/**
 * Application use-case orchestrating island creation, territory allocation,
 * authority acquisition, and initial bank provisioning.
 */
public final class CreateIslandUseCase {

    private static final Logger LOGGER = Logger.getLogger(CreateIslandUseCase.class.getName());

    public sealed interface CreateIslandResult {
        record Success(Island island, IslandLocation location, StarterPreset preset) implements CreateIslandResult {}

        record AlreadyHasIsland(IslandId existingIslandId) implements CreateIslandResult {}

        record UnknownPreset(String presetId) implements CreateIslandResult {}

        record Failure(String reason) implements CreateIslandResult {}
    }

    private final IslandStoragePort islandStoragePort;
    private final IslandAuthorityPort islandAuthorityPort;
    private final IslandBankPort islandBankPort;
    private final StarterPresetCatalog presetCatalog;
    private final WorldGridPort worldGridPort;
    private final WorldGridAllocationPort worldGridAllocationPort;
    private final @Nullable OutboxPort outboxPort;
    private final @Nullable GameModeHierarchyService gameModeHierarchy;

    /**
     * Holds one profile to one island creation at a time.
     *
     * <p>Keyed on the profile because that is who may have one island. A player is on one server at
     * a time, so this is the whole of the contention: the double click, the client that sent the
     * packet twice, the menu clicked while the first creation was still allocating a plot.
     */
    private final KeyedMutationLock<ProfileId> profileLock = new KeyedMutationLock<>();

    /** The lock a creation holds for its profile, for anything else that must not overlap one. */
    public KeyedMutationLock<ProfileId> profileLock() {
        return profileLock;
    }

    /**
     * How far a new island reaches from its centre.
     *
     * <p>It used to be the number 50, written twice in this file, and it did not match anything: the
     * first tier of the island size upgrade an operator can edit says 25. A new island now starts
     * wherever the island's own size allowance puts it, which for an island that has bought nothing
     * is the first tier of that upgrade, which is a line in the operator's file.
     */
    private final ToIntFunction<IslandId> startingRadius;

    /**
     * The radius a caller that names none gets. Only the convenience constructors use it; the wiring
     * always names one, read off the island size upgrade.
     */
    public static final int DEFAULT_STARTING_RADIUS = 25;

    /**
     * How high above the void a new island's spawn sits.
     *
     * <p>It used to be the number 100 written twice in this file. It is the height the starter
     * schematic is pasted at, which is a world decision rather than ours, so the wiring reads it out
     * of the world configuration and this is only what a caller that names none gets.
     */
    public static final int DEFAULT_ISLAND_SPAWN_Y = 100;

    /**
     * How long a new island's authority lease runs, when the caller names no number.
     *
     * <p>It used to be a day written here, and nothing renewed it, so the island could not use its
     * bank a day after it was made. The heartbeat renews it now and the operator sets the length.
     */
    public static final int DEFAULT_AUTHORITY_LEASE_SECONDS = 600;

    private final int islandSpawnY;
    private final int authorityLeaseSeconds;

    public CreateIslandUseCase(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            StarterPresetCatalog presetCatalog,
            WorldGridPort worldGridPort,
            WorldGridAllocationPort worldGridAllocationPort,
            @Nullable OutboxPort outboxPort) {
        this(
                islandStoragePort,
                islandAuthorityPort,
                islandBankPort,
                presetCatalog,
                worldGridPort,
                worldGridAllocationPort,
                outboxPort,
                null);
    }

    /**
     * The canonical constructor, carrying the game mode hierarchy a new island is bound into.
     *
     * <p>Every island belongs to one game mode instance, and the instance is what a backup names
     * when it says which world this island came from. Nothing ever wrote one, so every backup fell
     * through to an instance id synthesised from the owner's profile: a reference to a row that has
     * never existed. Binding the island here is what makes the reference real.
     */
    public CreateIslandUseCase(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            StarterPresetCatalog presetCatalog,
            WorldGridPort worldGridPort,
            WorldGridAllocationPort worldGridAllocationPort,
            @Nullable OutboxPort outboxPort,
            @Nullable GameModeHierarchyService gameModeHierarchy) {
        this(
                islandStoragePort,
                islandAuthorityPort,
                islandBankPort,
                presetCatalog,
                worldGridPort,
                worldGridAllocationPort,
                outboxPort,
                gameModeHierarchy,
                ignored -> DEFAULT_STARTING_RADIUS,
                DEFAULT_ISLAND_SPAWN_Y,
                DEFAULT_AUTHORITY_LEASE_SECONDS);
    }

    /** The canonical constructor, carrying how far a new island reaches. */
    public CreateIslandUseCase(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            StarterPresetCatalog presetCatalog,
            WorldGridPort worldGridPort,
            WorldGridAllocationPort worldGridAllocationPort,
            @Nullable OutboxPort outboxPort,
            @Nullable GameModeHierarchyService gameModeHierarchy,
            ToIntFunction<IslandId> startingRadius,
            int islandSpawnY) {
        this(
                islandStoragePort,
                islandAuthorityPort,
                islandBankPort,
                presetCatalog,
                worldGridPort,
                worldGridAllocationPort,
                outboxPort,
                gameModeHierarchy,
                startingRadius,
                islandSpawnY,
                DEFAULT_AUTHORITY_LEASE_SECONDS);
    }

    /** The canonical constructor, carrying how long the authority lease a new island gets runs. */
    public CreateIslandUseCase(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            StarterPresetCatalog presetCatalog,
            WorldGridPort worldGridPort,
            WorldGridAllocationPort worldGridAllocationPort,
            @Nullable OutboxPort outboxPort,
            @Nullable GameModeHierarchyService gameModeHierarchy,
            ToIntFunction<IslandId> startingRadius,
            int islandSpawnY,
            int authorityLeaseSeconds) {
        if (authorityLeaseSeconds < 1) {
            throw new IllegalArgumentException("authorityLeaseSeconds must be >= 1: " + authorityLeaseSeconds);
        }
        this.authorityLeaseSeconds = authorityLeaseSeconds;
        this.gameModeHierarchy = gameModeHierarchy;
        this.startingRadius = Objects.requireNonNull(startingRadius, "startingRadius must not be null");
        this.islandSpawnY = islandSpawnY;
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort must not be null");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.worldGridPort = Objects.requireNonNull(worldGridPort, "worldGridPort must not be null");
        this.worldGridAllocationPort =
                Objects.requireNonNull(worldGridAllocationPort, "worldGridAllocationPort must not be null");
        this.outboxPort = outboxPort;
    }

    public CreateIslandUseCase(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            StarterPresetCatalog presetCatalog,
            WorldGridPort worldGridPort,
            WorldGridAllocationPort worldGridAllocationPort) {
        this(
                islandStoragePort,
                islandAuthorityPort,
                islandBankPort,
                presetCatalog,
                worldGridPort,
                worldGridAllocationPort,
                null);
    }

    public CreateIslandResult execute(
            PlayerUuid playerUuid, ProfileId profileId, String presetId, ServerNodeId serverNodeId, String worldName) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(presetId, "presetId must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        return profileLock.inside(
                profileId, () -> createOnAllocatedPlot(playerUuid, profileId, presetId, serverNodeId, worldName));
    }

    /** Looks for an island the profile already has and builds one if it has none. Runs under the lock. */
    private CreateIslandResult createOnAllocatedPlot(
            PlayerUuid playerUuid, ProfileId profileId, String presetId, ServerNodeId serverNodeId, String worldName) {
        Optional<IslandId> existingIsland = islandStoragePort.findIslandIdByProfileId(profileId);
        if (existingIsland.isPresent()) {
            return new CreateIslandResult.AlreadyHasIsland(existingIsland.get());
        }

        Optional<StarterPreset> optPreset = presetCatalog.findById(presetId);
        if (optPreset.isEmpty()) {
            return new CreateIslandResult.UnknownPreset(presetId);
        }

        StarterPreset preset = optPreset.get();
        IslandId islandId = IslandId.of(UUID.randomUUID());

        WorldGridAllocation allocation = worldGridAllocationPort.allocateNext(serverNodeId, worldName, islandId);
        IslandCoordinates center = new IslandCoordinates(allocation.centerX(), allocation.centerZ());
        IslandBounds bounds = worldGridPort.createBounds(center, startingRadius.applyAsInt(islandId));

        Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());
        IslandLocation location = new IslandLocation(
                islandId, worldName, bounds, center.x() + 0.5, islandSpawnY + 1.0, center.z() + 0.5, 0.0f, 0.0f);

        String payload = String.format(
                "{\"islandId\":\"%s\",\"ownerPlayerUuid\":\"%s\",\"ownerProfileId\":\"%s\",\"presetId\":\"%s\"}",
                islandId.value(), playerUuid.value(), profileId.value(), JsonText.escaped(preset.id()));
        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(), "ISLAND_CREATED", islandId.value().toString(), payload)
                : null;

        try {
            islandStoragePort.saveIsland(island, location, outboxEvent);
            islandAuthorityPort.acquireAuthority(islandId, serverNodeId, authorityLeaseSeconds);
            islandBankPort.createBank(islandId);
            bindIntoGameModeHierarchy(profileId, islandId, preset.id());
            return new CreateIslandResult.Success(island, location, preset);
        } catch (Exception e) {
            Optional<IslandId> existing = islandStoragePort.findIslandIdByProfileId(profileId);
            if (existing.isPresent()) {
                return new CreateIslandResult.AlreadyHasIsland(existing.get());
            }
            return new CreateIslandResult.Failure(e.getMessage() != null ? e.getMessage() : "Unknown storage error");
        }
    }

    public CreateIslandResult execute(
            PlayerUuid playerUuid,
            ProfileId profileId,
            String presetId,
            ServerNodeId serverNodeId,
            String worldName,
            long sequenceIndex) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(presetId, "presetId must not be null");
        Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        Objects.requireNonNull(worldName, "worldName must not be null");

        return profileLock.inside(
                profileId,
                () -> createAtSequence(playerUuid, profileId, presetId, serverNodeId, worldName, sequenceIndex));
    }

    /** The same, for a caller that names the grid slot itself. Runs under the lock. */
    private CreateIslandResult createAtSequence(
            PlayerUuid playerUuid,
            ProfileId profileId,
            String presetId,
            ServerNodeId serverNodeId,
            String worldName,
            long sequenceIndex) {
        Optional<IslandId> existingIsland = islandStoragePort.findIslandIdByProfileId(profileId);
        if (existingIsland.isPresent()) {
            return new CreateIslandResult.AlreadyHasIsland(existingIsland.get());
        }

        Optional<StarterPreset> optPreset = presetCatalog.findById(presetId);
        if (optPreset.isEmpty()) {
            return new CreateIslandResult.UnknownPreset(presetId);
        }

        StarterPreset preset = optPreset.get();
        IslandId islandId = IslandId.of(UUID.randomUUID());

        IslandCoordinates center = worldGridPort.allocateCenter(sequenceIndex);
        IslandBounds bounds = worldGridPort.createBounds(center, startingRadius.applyAsInt(islandId));

        Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());
        IslandLocation location = new IslandLocation(
                islandId, worldName, bounds, center.x() + 0.5, islandSpawnY + 1.0, center.z() + 0.5, 0.0f, 0.0f);

        String payload = String.format(
                "{\"islandId\":\"%s\",\"ownerPlayerUuid\":\"%s\",\"ownerProfileId\":\"%s\",\"presetId\":\"%s\"}",
                islandId.value(), playerUuid.value(), profileId.value(), JsonText.escaped(preset.id()));
        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(), "ISLAND_CREATED", islandId.value().toString(), payload)
                : null;

        try {
            worldGridAllocationPort.reserveNextSequence(serverNodeId, worldName, center.x(), center.z(), islandId);
            islandStoragePort.saveIsland(island, location, outboxEvent);
            islandAuthorityPort.acquireAuthority(islandId, serverNodeId, authorityLeaseSeconds);
            islandBankPort.createBank(islandId);
            return new CreateIslandResult.Success(island, location, preset);
        } catch (Exception e) {
            Optional<IslandId> existing = islandStoragePort.findIslandIdByProfileId(profileId);
            if (existing.isPresent()) {
                return new CreateIslandResult.AlreadyHasIsland(existing.get());
            }
            return new CreateIslandResult.Failure(e.getMessage() != null ? e.getMessage() : "Unknown storage error");
        }
    }

    /**
     * Binds the new island into its owner's game mode instance, creating the instance when it is
     * their first island.
     *
     * <p>A failure here never fails the creation. The player's island exists, their bank exists and
     * this node holds authority over it; refusing all of that because a hierarchy row would not
     * write would be a worse answer than a backup that falls back to a synthesised reference, which
     * is exactly what every backup did before this ran at all.
     */
    private void bindIntoGameModeHierarchy(ProfileId profileId, IslandId islandId, String rulesetConfig) {
        if (gameModeHierarchy == null) {
            return;
        }
        try {
            GameModeInstance instance = gameModeHierarchy.getOrCreateSkyblockInstance(profileId, rulesetConfig);
            gameModeHierarchy.bindIsland(instance.id(), islandId);
        } catch (RuntimeException e) {
            LOGGER.log(
                    Level.WARNING,
                    e,
                    () -> "The island " + islandId + " was created but could not be bound into its game mode "
                            + "instance. Its backups will name a synthesised reference until it is bound.");
        }
    }
}
