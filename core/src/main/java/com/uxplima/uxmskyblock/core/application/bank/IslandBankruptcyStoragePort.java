package com.uxplima.uxmskyblock.core.application.bank;

import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.bank.IslandBankruptcyRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * Outbound persistence port for island bankruptcy records and debt tracking (Section 2.39).
 */
public interface IslandBankruptcyStoragePort {

    /**
     * Retrieves the bankruptcy record for a specific island, if one exists.
     */
    Optional<IslandBankruptcyRecord> findByIslandId(IslandId islandId);

    /**
     * Inserts or updates the bankruptcy record for an island.
     */
    void save(IslandBankruptcyRecord record);

    /**
     * Retrieves all active bankruptcy records across the network (GRACE or LOCKED).
     */
    List<IslandBankruptcyRecord> findAllBankruptcies();

    /**
     * Deletes the bankruptcy record when an island is deleted or reset.
     */
    void deleteByIslandId(IslandId islandId);
}
