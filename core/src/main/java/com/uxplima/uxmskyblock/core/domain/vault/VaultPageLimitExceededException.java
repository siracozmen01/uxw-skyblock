package com.uxplima.uxmskyblock.core.domain.vault;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Thrown when the requested vault page exceeds the unlocked page limit for the island.
 */
public final class VaultPageLimitExceededException extends RuntimeException {

    private final IslandId islandId;
    private final int requestedPage;
    private final int maxAllowedPages;

    public VaultPageLimitExceededException(IslandId islandId, int requestedPage, int maxAllowedPages) {
        super("Requested vault page " + requestedPage + " exceeds unlocked limit of " + maxAllowedPages + " for island "
                + islandId);
        this.islandId = Objects.requireNonNull(islandId, "islandId must not be null");
        this.requestedPage = requestedPage;
        this.maxAllowedPages = maxAllowedPages;
    }

    public IslandId islandId() {
        return islandId;
    }

    public int requestedPage() {
        return requestedPage;
    }

    public int maxAllowedPages() {
        return maxAllowedPages;
    }
}
