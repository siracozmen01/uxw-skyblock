package com.uxplima.uxmskyblock.core.application.dimension;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.dimension.DimensionMapping;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionAccessResult;
import com.uxplima.uxmskyblock.core.domain.dimension.IslandDimensionType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import org.jspecify.annotations.Nullable;

/**
 * Pure domain application service orchestrating cross-dimension travel, portal linkage,
 * upgrade progression gates, and private island coordinate mappings (Sections 2.27 & 2.37).
 */
public final class IslandDimensionService {

    private final IslandUpgradeStoragePort upgradeStoragePort;
    private final Map<IslandDimensionType, DimensionMapping> dimensionMappings;
    private final @Nullable IslandDimensionStoragePort dimensionStoragePort;
    private final ConcurrentHashMap<IslandId, Set<IslandDimensionType>> generatedDimensions = new ConcurrentHashMap<>();

    public IslandDimensionService(
            IslandUpgradeStoragePort upgradeStoragePort,
            Map<IslandDimensionType, DimensionMapping> dimensionMappings,
            @Nullable IslandDimensionStoragePort dimensionStoragePort) {
        this.upgradeStoragePort = Objects.requireNonNull(upgradeStoragePort, "upgradeStoragePort must not be null");
        Objects.requireNonNull(dimensionMappings, "dimensionMappings must not be null");
        this.dimensionStoragePort = dimensionStoragePort;
        EnumMap<IslandDimensionType, DimensionMapping> copy = new EnumMap<>(IslandDimensionType.class);
        copy.putAll(dimensionMappings);
        this.dimensionMappings = Collections.unmodifiableMap(copy);
    }

    public IslandDimensionService(
            IslandUpgradeStoragePort upgradeStoragePort, Map<IslandDimensionType, DimensionMapping> dimensionMappings) {
        this(upgradeStoragePort, dimensionMappings, null);
    }

    /**
     * Evaluates whether an island has permission, progression unlocking, and active configuration
     * to access the specified dimension.
     *
     * @param islandId target island identity
     * @param dimension target dimension environment
     * @return typed result outcome
     */
    public IslandDimensionAccessResult checkAccess(IslandId islandId, IslandDimensionType dimension) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");

        DimensionMapping mapping = dimensionMappings.get(dimension);
        if (mapping == null || !mapping.isEnabled()) {
            return new IslandDimensionAccessResult.Disabled(dimension);
        }

        if (mapping.requiredUpgrade() != null) {
            int tier = upgradeStoragePort.getUpgradeTier(islandId, mapping.requiredUpgrade());
            if (tier < 1) {
                return new IslandDimensionAccessResult.Locked(dimension, mapping.requiredUpgrade());
            }
        }

        boolean schematicNeeded = mapping.isPrivateIsland() && !hasGeneratedDimension(islandId, dimension);
        return new IslandDimensionAccessResult.Allowed(dimension, mapping.mode(), mapping.worldName(), schematicNeeded);
    }

    /**
     * Convenience check returning true if the island can currently enter the given dimension.
     */
    public boolean canAccessDimension(IslandId islandId, IslandDimensionType dimension) {
        return checkAccess(islandId, dimension) instanceof IslandDimensionAccessResult.Allowed;
    }

    /**
     * Marks that the starter platform/outpost schematic for this dimension has been pasted.
     */
    public void markDimensionGenerated(IslandId islandId, IslandDimensionType dimension) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");
        generatedDimensions
                .computeIfAbsent(islandId, k -> ConcurrentHashMap.newKeySet())
                .add(dimension);
        if (dimensionStoragePort != null) {
            dimensionStoragePort.markDimensionGenerated(islandId, dimension);
        }
    }

    /**
     * Returns true if the island has already had its starter platform generated for this dimension.
     */
    public boolean hasGeneratedDimension(IslandId islandId, IslandDimensionType dimension) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(dimension, "dimension must not be null");
        Set<IslandDimensionType> set = generatedDimensions.get(islandId);
        if (set != null && set.contains(dimension)) {
            return true;
        }
        if (dimensionStoragePort != null && dimensionStoragePort.hasGeneratedDimension(islandId, dimension)) {
            generatedDimensions
                    .computeIfAbsent(islandId, k -> ConcurrentHashMap.newKeySet())
                    .add(dimension);
            return true;
        }
        return false;
    }

    /**
     * Cleans up all generated dimension state flags when an island is deleted or reset.
     */
    public void resetIslandDimensions(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        generatedDimensions.remove(islandId);
        if (dimensionStoragePort != null) {
            dimensionStoragePort.deleteIslandDimensions(islandId);
        }
    }

    /**
     * Retrieves the dimension mapping specification for a given dimension type.
     */
    public Optional<DimensionMapping> mapping(IslandDimensionType dimension) {
        Objects.requireNonNull(dimension, "dimension must not be null");
        return Optional.ofNullable(dimensionMappings.get(dimension));
    }

    public Map<IslandDimensionType, DimensionMapping> allMappings() {
        return dimensionMappings;
    }
}
