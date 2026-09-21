package com.uxplima.uxmskyblock.core.application.warp;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.visit.IslandVisitRule;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeDefinition;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeTier;
import com.uxplima.uxmskyblock.core.domain.warp.DuplicateWarpNameException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import com.uxplima.uxmskyblock.core.domain.warp.IslandClosedToVisitorsException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandLockedException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.PlayerBannedFromIslandException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLockedException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpNotFoundException;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain service governing named island warps, Folia safe teleport anti-trap evaluation,
 * visitor security, and public community explorer indexing.
 */
public final class IslandWarpService {

    public static final int DEFAULT_BASE_WARPS = 2;

    private final IslandWarpStoragePort storagePort;
    private final SafeTeleportEngine safeTeleportEngine;
    private final @Nullable IslandUpgradeService upgradeService;
    private final int baseWarpLimit;
    private final @Nullable BiPredicate<IslandId, ProfileId> allyAccessChecker;

    /**
     * Makes counting the warps and writing the next one one thing.
     *
     * <p>The count is read, compared to what the island's upgrade allows, and then a warp is
     * written. Two of those at once both read one below the limit, both pass, and both write: the
     * island ends up with one more warp than the upgrade an operator sold them allows.
     */
    private final IslandMutationLock mutationLock;

    public IslandWarpService(
            IslandWarpStoragePort storagePort,
            SafeTeleportEngine safeTeleportEngine,
            @Nullable IslandUpgradeService upgradeService,
            int baseWarpLimit,
            @Nullable BiPredicate<IslandId, ProfileId> allyAccessChecker,
            IslandMutationLock mutationLock) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.safeTeleportEngine = Objects.requireNonNull(safeTeleportEngine, "safeTeleportEngine must not be null");
        this.upgradeService = upgradeService;
        if (baseWarpLimit < 1) {
            throw new IllegalArgumentException("baseWarpLimit must be >= 1: " + baseWarpLimit);
        }
        this.baseWarpLimit = baseWarpLimit;
        this.allyAccessChecker = allyAccessChecker;
        this.mutationLock = java.util.Objects.requireNonNull(mutationLock, "mutationLock must not be null");
    }

    public IslandWarpService(
            IslandWarpStoragePort storagePort,
            SafeTeleportEngine safeTeleportEngine,
            @Nullable IslandUpgradeService upgradeService) {
        this(storagePort, safeTeleportEngine, upgradeService, DEFAULT_BASE_WARPS, null, new IslandMutationLock());
    }

    /** The shape the tests and the older callers use, with a lock of its own. */
    public IslandWarpService(
            IslandWarpStoragePort storagePort,
            SafeTeleportEngine safeTeleportEngine,
            @Nullable IslandUpgradeService upgradeService,
            int baseWarpLimit,
            @Nullable BiPredicate<IslandId, ProfileId> allyAccessChecker) {
        this(
                storagePort,
                safeTeleportEngine,
                upgradeService,
                baseWarpLimit,
                allyAccessChecker,
                new IslandMutationLock());
    }

    /**
     * Calculates the maximum number of warps allowed for an island based on base limits and upgrade tiers.
     */
    public int getMaxAllowedWarps(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        if (upgradeService == null) {
            return baseWarpLimit;
        }

        int tier = upgradeService.getCurrentTier(islandId, UpgradeId.WARPS);
        if (tier <= 0) {
            return baseWarpLimit;
        }

        Optional<UpgradeDefinition> defOpt = upgradeService.getDefinition(UpgradeId.WARPS);
        if (defOpt.isPresent()) {
            Optional<UpgradeTier> tierOpt = defOpt.get().getTier(tier);
            if (tierOpt.isPresent()) {
                Double limitProp = tierOpt.get().properties().get("limit");
                if (limitProp == null) {
                    limitProp = tierOpt.get().properties().get("warps");
                }
                if (limitProp != null) {
                    return limitProp.intValue();
                }
            }
        }

        return baseWarpLimit + tier;
    }

    /**
     * Creates and persists a new named island warp after validating permissions, bounds, limits, and uniqueness.
     */
    public IslandWarp createWarp(
            Island island,
            ProfileId actorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            WarpLocation location,
            WarpCategory category,
            String iconMaterial) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");
        Objects.requireNonNull(location, "location must not be null");
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(iconMaterial, "iconMaterial must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.WARP_CREATE)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to create warps on island " + island.id());
        }

        if (!island.bounds().contains(location.blockX(), location.blockZ())) {
            throw new IllegalArgumentException("Warp location (" + location.blockX() + ", " + location.blockZ()
                    + ") is outside island bounds: " + island.bounds());
        }

        // Counting and writing is one thing. Without that the count is a number two callers can
        // both read one below the limit, and the island gets one more warp than it paid for.
        return mutationLock.inside(island.id(), () -> writeWarp(island, warpName, location, category, iconMaterial));
    }

    /** Counts what is there, refuses or writes. Runs inside the island's mutation lock. */
    private IslandWarp writeWarp(
            Island island,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            WarpLocation location,
            WarpCategory category,
            String iconMaterial) {
        int currentCount = storagePort.countWarpsByIsland(island.id());
        int maxAllowed = getMaxAllowedWarps(island.id());
        if (currentCount >= maxAllowed) {
            throw new WarpLimitExceededException(island.id(), currentCount, maxAllowed);
        }

        if (storagePort.findWarpByName(island.id(), warpName).isPresent()) {
            throw new DuplicateWarpNameException(island.id(), warpName);
        }

        IslandWarpId id = IslandWarpId.random();
        Instant now = Instant.now();
        IslandWarp warp = new IslandWarp(id, island.id(), warpName, location, iconMaterial, category, false, now, now);
        storagePort.saveWarp(warp);
        return warp;
    }

    /**
     * Deletes a warp by name.
     */
    public void deleteWarp(
            Island island, ProfileId actorProfileId, com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.WARP_DELETE)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to delete warps on island " + island.id());
        }

        boolean deleted = storagePort.deleteWarp(island.id(), warpName);
        if (!deleted) {
            throw new WarpNotFoundException(island.id(), warpName.value());
        }
    }

    /**
     * Sets granular lock status on a specific warp.
     */
    public IslandWarp setWarpLock(
            Island island,
            ProfileId actorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            boolean locked) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.WARP_CREATE)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to modify warps on island " + island.id());
        }

        IslandWarp warp = storagePort
                .findWarpByName(island.id(), warpName)
                .orElseThrow(() -> new WarpNotFoundException(island.id(), warpName.value()));

        IslandWarp updated = warp.withLocked(locked);
        storagePort.saveWarp(updated);
        return updated;
    }

    /**
     * Updates warp category for community explorer directory indexing.
     */
    public IslandWarp setWarpCategory(
            Island island,
            ProfileId actorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            WarpCategory category) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");
        Objects.requireNonNull(category, "category must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.WARP_CREATE)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to modify warps on island " + island.id());
        }

        IslandWarp warp = storagePort
                .findWarpByName(island.id(), warpName)
                .orElseThrow(() -> new WarpNotFoundException(island.id(), warpName.value()));

        IslandWarp updated = warp.withCategory(category);
        storagePort.saveWarp(updated);
        return updated;
    }

    /**
     * Updates warp display icon material.
     */
    public IslandWarp setWarpIcon(
            Island island,
            ProfileId actorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            String iconMaterial) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");
        Objects.requireNonNull(iconMaterial, "iconMaterial must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.WARP_CREATE)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to modify warps on island " + island.id());
        }

        IslandWarp warp = storagePort
                .findWarpByName(island.id(), warpName)
                .orElseThrow(() -> new WarpNotFoundException(island.id(), warpName.value()));

        IslandWarp updated = warp.withIconMaterial(iconMaterial);
        storagePort.saveWarp(updated);
        return updated;
    }

    /**
     * Relocates an existing warp to new coordinates.
     */
    public IslandWarp relocateWarp(
            Island island,
            ProfileId actorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            WarpLocation newLocation) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");
        Objects.requireNonNull(newLocation, "newLocation must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.WARP_CREATE)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to modify warps on island " + island.id());
        }

        if (!island.bounds().contains(newLocation.blockX(), newLocation.blockZ())) {
            throw new IllegalArgumentException("Warp location (" + newLocation.blockX() + ", " + newLocation.blockZ()
                    + ") is outside island bounds: " + island.bounds());
        }

        IslandWarp warp = storagePort
                .findWarpByName(island.id(), warpName)
                .orElseThrow(() -> new WarpNotFoundException(island.id(), warpName.value()));

        IslandWarp updated = warp.withLocation(newLocation);
        storagePort.saveWarp(updated);
        return updated;
    }

    /**
     * Bans a visitor player from the island.
     */
    public IslandBan banPlayer(
            Island island, ProfileId actorProfileId, PlayerUuid targetPlayerUuid, @Nullable String reason) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.MEMBER_KICK)) {
            throw new SecurityException(
                    "Profile " + actorProfileId + " does not have permission to ban players on island " + island.id());
        }

        if (island.ownerPlayerUuid().equals(targetPlayerUuid)) {
            throw new IllegalArgumentException("Cannot ban island owner: " + targetPlayerUuid);
        }

        boolean isMember = island.members().values().stream()
                .anyMatch(member -> member.playerUuid().equals(targetPlayerUuid));
        if (isMember) {
            throw new IllegalArgumentException("Cannot ban an active island member: " + targetPlayerUuid);
        }

        IslandBan ban = new IslandBan(island.id(), targetPlayerUuid, actorProfileId, reason, Instant.now());
        storagePort.banPlayer(ban);
        return ban;
    }

    /**
     * Unbans a player from visiting the island.
     */
    public boolean unbanPlayer(Island island, ProfileId actorProfileId, PlayerUuid targetPlayerUuid) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(actorProfileId, "actorProfileId must not be null");
        Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid must not be null");

        if (!hasWarpPermission(island, actorProfileId, IslandPermission.MEMBER_KICK)) {
            throw new SecurityException("Profile " + actorProfileId
                    + " does not have permission to unban players on island " + island.id());
        }

        return storagePort.unbanPlayer(island.id(), targetPlayerUuid);
    }

    public boolean isPlayerBanned(IslandId islandId, PlayerUuid playerUuid) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        return storagePort.isPlayerBanned(islandId, playerUuid);
    }

    /**
     * Prepares a visit to an island warp. Validates visitor ban status, island lock status,
     * warp lock status, and executes Folia-safe anti-trap destination verification.
     *
     * @param island target island aggregate
     * @param visitorUuid visitor player UUID
     * @param visitorProfileId visitor active profile ID
     * @param warpName target warp name
     * @param inspector block inspection adapter
     * @return safe destination location verified by {@link SafeTeleportEngine}
     */
    public WarpLocation prepareVisit(
            Island island,
            PlayerUuid visitorUuid,
            ProfileId visitorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName,
            SafeBlockInspector inspector) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(visitorUuid, "visitorUuid must not be null");
        Objects.requireNonNull(visitorProfileId, "visitorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");
        Objects.requireNonNull(inspector, "inspector must not be null");

        return safeSpotFor(resolveVisit(island, visitorUuid, visitorProfileId, warpName), inspector);
    }

    /**
     * The gate and the warp, without any block read.
     *
     * <p>This is the half a caller may run off the region thread: the ban list, the island's flags
     * and the warp row are all storage. The safe spot search reads blocks, and under Folia a block
     * belongs to the region thread that owns it, so {@link #safeSpotFor} is a second step rather
     * than part of this one. {@link #prepareVisit} is the two together, for a caller that already
     * holds the right thread.
     */
    public IslandWarp resolveVisit(
            Island island,
            PlayerUuid visitorUuid,
            ProfileId visitorProfileId,
            com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName) {
        Objects.requireNonNull(island, "island must not be null");
        Objects.requireNonNull(visitorUuid, "visitorUuid must not be null");
        Objects.requireNonNull(visitorProfileId, "visitorProfileId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");

        boolean banned = storagePort.isPlayerBanned(island.id(), visitorUuid);
        boolean privilegedAlly = allyAccessChecker != null && allyAccessChecker.test(island.id(), visitorProfileId);
        switch (IslandVisitRule.decide(island, visitorProfileId, banned, privilegedAlly)) {
            case IslandVisitRule.Decision.Allowed ignored -> {
                // Carry on to the warp itself.
            }
            case IslandVisitRule.Decision.Banned ignored ->
                throw new PlayerBannedFromIslandException(island.id(), visitorUuid, null);
            case IslandVisitRule.Decision.Locked ignored -> throw new IslandLockedException(island.id());
            case IslandVisitRule.Decision.ClosedToVisitors ignored ->
                throw new IslandClosedToVisitorsException(island.id());
        }

        IslandWarp warp = storagePort
                .findWarpByName(island.id(), warpName)
                .orElseThrow(() -> new WarpNotFoundException(island.id(), warpName.value()));
        if (warp.isLocked() && !island.isMember(visitorProfileId)) {
            throw new WarpLockedException(warpName);
        }
        return warp;
    }

    /**
     * The safe spot for a warp, which is the half that reads blocks and belongs to the region
     * thread that owns them.
     */
    public WarpLocation safeSpotFor(IslandWarp warp, SafeBlockInspector inspector) {
        Objects.requireNonNull(warp, "warp must not be null");
        Objects.requireNonNull(inspector, "inspector must not be null");
        return safeTeleportEngine.verifyOrFindSafeSpot(warp.location(), inspector);
    }

    public List<IslandWarp> getWarps(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return storagePort.findWarpsByIsland(islandId);
    }

    public Optional<IslandWarp> getWarp(IslandId islandId, com.uxplima.uxmskyblock.core.domain.warp.WarpName warpName) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(warpName, "warpName must not be null");
        return storagePort.findWarpByName(islandId, warpName);
    }

    public List<IslandWarp> getPublicWarps(int limit, int offset) {
        return storagePort.findPublicWarps(limit, offset);
    }

    public List<IslandWarp> getPublicWarpsByCategory(WarpCategory category, int limit, int offset) {
        Objects.requireNonNull(category, "category must not be null");
        return storagePort.findPublicWarpsByCategory(category, limit, offset);
    }

    public List<IslandBan> getBans(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return storagePort.findBansByIsland(islandId);
    }

    private boolean hasWarpPermission(Island island, ProfileId profileId, IslandPermission permission) {
        if (island.isOwner(profileId)) {
            return true;
        }
        return island.hasPermission(profileId, permission);
    }
}
