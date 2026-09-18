package com.uxplima.uxmskyblock.core.domain.vault;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when an island vault page cannot be found.
 */
public final class VaultPageNotFoundException extends RuntimeException {

    private final IslandId islandId;
    private final int page;

    public VaultPageNotFoundException(IslandId islandId, int page) {
        super("Vault page " + page + " does not exist on island " + islandId);
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.page = page;
    }

    public IslandId islandId() {
        return islandId;
    }

    public int page() {
        return page;
    }
}
