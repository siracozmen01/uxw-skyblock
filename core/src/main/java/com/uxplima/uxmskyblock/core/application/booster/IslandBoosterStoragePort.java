package com.uxplima.uxmskyblock.core.application.booster;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Storage port for island booster records.
 */
public interface IslandBoosterStoragePort {

    /**
     * Persists or updates an individual booster record.
     */
    void saveBooster(IslandBooster booster);

    /**
     * Persists or updates multiple booster records.
     */
    void saveAll(Collection<IslandBooster> boosters);

    /**
     * Finds a booster by its unique ID.
     */
    Optional<IslandBooster> findById(UUID boosterId);

    /**
     * Finds all boosters associated with an island.
     */
    List<IslandBooster> findByIsland(IslandId islandId);

    /**
     * Finds all boosters for a specific island and category.
     */
    List<IslandBooster> findByIslandAndCategory(IslandId islandId, BoosterCategory category);

    /**
     * Deletes a booster by its ID.
     */
    void deleteById(UUID boosterId);

    /**
     * Deletes all boosters belonging to an island.
     */
    void deleteByIsland(IslandId islandId);

    /**
     * Purges expired, unpaused boosters whose expiresAt is before {@code now}.
     *
     * @param now current instant
     * @return number of purged records
     */
    int purgeExpired(Instant now);
}
