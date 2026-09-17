package com.uxplima.uxmskyblock.core.application.world;

import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.world.IslandCoordinates;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;

/**
 * Default mathematical implementation of {@link WorldGridPort} powered by
 * {@link SpiralGridCoordinateAllocator}.
 */
public final class SpiralWorldGridService implements WorldGridPort {

    private final SpiralGridCoordinateAllocator allocator;

    public SpiralWorldGridService(SpiralGridCoordinateAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator must not be null");
    }

    public SpiralWorldGridService() {
        this(new SpiralGridCoordinateAllocator());
    }

    @Override
    public IslandCoordinates allocateCenter(long sequenceIndex) {
        return allocator.coordinatesForIndex(sequenceIndex);
    }

    @Override
    public IslandBounds createBounds(IslandCoordinates center, int initialRadius) {
        Objects.requireNonNull(center, "center must not be null");
        return IslandBounds.fromCenterAndRadius(center.x(), center.z(), initialRadius);
    }

    @Override
    public Optional<Long> resolveSequenceIndex(int x, int z) {
        long idx = allocator.indexForCoordinates(x, z);
        return idx >= 0 ? Optional.of(idx) : Optional.empty();
    }
}
