package com.uxplima.uxmskyblock.core.application.island;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridPort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
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

    public CreateIslandUseCase(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            StarterPresetCatalog presetCatalog,
            WorldGridPort worldGridPort,
            WorldGridAllocationPort worldGridAllocationPort,
            @Nullable OutboxPort outboxPort) {
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
        int initialRadius = 50;
        IslandBounds bounds = worldGridPort.createBounds(center, initialRadius);

        Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());
        int spawnY = 100;
        IslandLocation location = new IslandLocation(
                islandId, worldName, bounds, center.x() + 0.5, spawnY + 1.0, center.z() + 0.5, 0.0f, 0.0f);

        try {
            islandStoragePort.saveIsland(island, location);
            islandAuthorityPort.acquireAuthority(islandId, serverNodeId, 86400);
            islandBankPort.createBank(islandId);
            stageIslandCreatedEvent(islandId, playerUuid, profileId, preset);
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
        int initialRadius = 50;
        IslandBounds bounds = worldGridPort.createBounds(center, initialRadius);

        Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());
        int spawnY = 100;
        IslandLocation location = new IslandLocation(
                islandId, worldName, bounds, center.x() + 0.5, spawnY + 1.0, center.z() + 0.5, 0.0f, 0.0f);

        try {
            worldGridAllocationPort.reserveNextSequence(serverNodeId, worldName, center.x(), center.z(), islandId);
            islandStoragePort.saveIsland(island, location);
            islandAuthorityPort.acquireAuthority(islandId, serverNodeId, 86400);
            islandBankPort.createBank(islandId);
            stageIslandCreatedEvent(islandId, playerUuid, profileId, preset);
            return new CreateIslandResult.Success(island, location, preset);
        } catch (Exception e) {
            Optional<IslandId> existing = islandStoragePort.findIslandIdByProfileId(profileId);
            if (existing.isPresent()) {
                return new CreateIslandResult.AlreadyHasIsland(existing.get());
            }
            return new CreateIslandResult.Failure(e.getMessage() != null ? e.getMessage() : "Unknown storage error");
        }
    }

    private void stageIslandCreatedEvent(
            IslandId islandId, PlayerUuid playerUuid, ProfileId profileId, StarterPreset preset) {
        if (outboxPort != null) {
            String payload = String.format(
                    "{\"islandId\":\"%s\",\"ownerPlayerUuid\":\"%s\",\"ownerProfileId\":\"%s\",\"presetId\":\"%s\"}",
                    islandId.value(), playerUuid.value(), profileId.value(), preset.id());
            outboxPort.stageEvent(
                    EventId.random(), "ISLAND_CREATED", islandId.value().toString(), payload);
        }
    }
}
