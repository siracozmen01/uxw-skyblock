package com.uxplima.uxmskyblock.bukkit.webmap;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Composite web map adapter that broadcasts island markers and boundaries across
 * all actively enabled web map integrations (Dynmap, BlueMap, Pl3xMap) (Section 2.42).
 */
public final class CompositeWebMapAdapter implements WebMapAdapter {

    private final List<WebMapAdapter> delegates;

    public CompositeWebMapAdapter(List<WebMapAdapter> delegates) {
        Objects.requireNonNull(delegates, "delegates must not be null");
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public void registerIslandMarker(
            IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        for (WebMapAdapter delegate : delegates) {
            if (delegate.isAvailable()) {
                delegate.registerIslandMarker(islandId, islandName, worldName, x, y, z);
            }
        }
    }

    @Override
    public void updateIslandMarker(
            IslandId islandId, String islandName, String worldName, double x, double y, double z) {
        for (WebMapAdapter delegate : delegates) {
            if (delegate.isAvailable()) {
                delegate.updateIslandMarker(islandId, islandName, worldName, x, y, z);
            }
        }
    }

    @Override
    public void removeIslandMarker(IslandId islandId) {
        for (WebMapAdapter delegate : delegates) {
            if (delegate.isAvailable()) {
                delegate.removeIslandMarker(islandId);
            }
        }
    }

    @Override
    public String providerName() {
        List<String> available = delegates.stream()
                .filter(WebMapAdapter::isAvailable)
                .map(WebMapAdapter::providerName)
                .toList();

        if (available.isEmpty()) {
            return "NONE";
        }
        return "COMPOSITE (" + String.join(", ", available) + ")";
    }

    @Override
    public boolean isAvailable() {
        for (WebMapAdapter delegate : delegates) {
            if (delegate.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    public List<WebMapAdapter> delegates() {
        return delegates;
    }
}
