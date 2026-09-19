package com.uxplima.uxmskyblock.core.application.name;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import org.jspecify.annotations.Nullable;

/**
 * Application service managing island naming lifecycle, uniqueness checks,
 * profanity suppression, and audit event dispatching (Section 2.41).
 */
public final class IslandNameService {

    private static final Set<String> RESERVED_NAMES = Set.of(
            "admin", "administrator", "staff", "spawn", "hub", "lobby", "official", "null", "undefined", "system");

    private final IslandNameStoragePort nameStoragePort;
    private final IslandStoragePort islandStoragePort;
    private final IslandAccessService accessService;
    private final @Nullable OutboxPort outboxPort;
    private final Set<String> profanityFilter;

    public IslandNameService(
            IslandNameStoragePort nameStoragePort,
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable OutboxPort outboxPort) {
        this(nameStoragePort, islandStoragePort, accessService, outboxPort, Collections.emptySet());
    }

    public IslandNameService(
            IslandNameStoragePort nameStoragePort,
            IslandStoragePort islandStoragePort,
            IslandAccessService accessService,
            @Nullable OutboxPort outboxPort,
            Set<String> profanityWords) {
        this.nameStoragePort = Objects.requireNonNull(nameStoragePort, "nameStoragePort must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.accessService = Objects.requireNonNull(accessService, "accessService must not be null");
        this.outboxPort = outboxPort;
        this.profanityFilter = new HashSet<>();
        for (String word : profanityWords) {
            this.profanityFilter.add(word.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Assigns a custom name to an island after validating caller authorization and name rules.
     *
     * @param islandId target island id
     * @param callerProfile profile attempting rename
     * @param rawName candidate name string
     * @return the assigned IslandName
     */
    public IslandName renameIsland(IslandId islandId, ProfileId callerProfile, String rawName) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(callerProfile, "callerProfile must not be null");
        Objects.requireNonNull(rawName, "rawName must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        // Verify caller has permission to rename (owner or SETTINGS_MODIFY permission)
        if (!accessService.checkPermission(island, callerProfile, IslandPermission.SETTINGS_MODIFY)) {
            throw new SecurityException("Profile " + callerProfile + " lacks permission to rename island " + islandId);
        }

        IslandName islandName = IslandName.of(rawName);
        String normalized = islandName.value().toLowerCase(Locale.ROOT);

        // Reserved names check
        if (RESERVED_NAMES.contains(normalized)) {
            throw new IllegalArgumentException("Island name '" + islandName.value() + "' is a reserved keyword");
        }

        // Profanity filter check
        for (String badWord : profanityFilter) {
            if (normalized.contains(badWord)) {
                throw new IllegalArgumentException("Island name violates language safety policy");
            }
        }

        // Uniqueness check
        Optional<IslandId> existing = nameStoragePort.findIslandIdByName(islandName.value());
        if (existing.isPresent() && !existing.get().equals(islandId)) {
            throw new IllegalStateException("Island name '" + islandName.value() + "' is already taken");
        }

        StagedOutboxEvent renameEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_RENAMED",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"callerProfile\":\"%s\",\"newName\":\"%s\",\"timestamp\":\"%s\"}",
                                islandId.value(), callerProfile.value(), islandName.value(), Instant.now()))
                : null;

        nameStoragePort.updateCustomName(islandId, islandName, renameEvent);

        return islandName;
    }

    /**
     * Resets custom name of the island to default (null).
     */
    public void resetIslandName(IslandId islandId, ProfileId callerProfile) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(callerProfile, "callerProfile must not be null");

        Island island = islandStoragePort
                .findIslandById(islandId)
                .orElseThrow(() -> new IllegalArgumentException("Island not found: " + islandId));

        if (!accessService.checkPermission(island, callerProfile, IslandPermission.SETTINGS_MODIFY)) {
            throw new SecurityException(
                    "Profile " + callerProfile + " lacks permission to reset island name " + islandId);
        }

        StagedOutboxEvent resetEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_NAME_RESET",
                        islandId.value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"callerProfile\":\"%s\",\"timestamp\":\"%s\"}",
                                islandId.value(), callerProfile.value(), Instant.now()))
                : null;

        nameStoragePort.updateCustomName(islandId, null, resetEvent);
    }

    public Optional<IslandName> getIslandName(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return nameStoragePort.findCustomName(islandId);
    }

    public Optional<IslandId> resolveIslandIdByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return nameStoragePort.findIslandIdByName(name);
    }
}
