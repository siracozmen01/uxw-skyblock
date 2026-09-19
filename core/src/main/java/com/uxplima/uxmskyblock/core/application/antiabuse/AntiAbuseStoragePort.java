package com.uxplima.uxmskyblock.core.application.antiabuse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.antiabuse.IslandQuarantineRecord;
import com.uxplima.uxmskyblock.core.domain.antiabuse.PlayerAntiAbuseRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Outbound application port for persistent storage of player anti-abuse records
 * and active island starter quarantine windows.
 */
public interface AntiAbuseStoragePort {

    /**
     * Loads the anti-abuse record for a player, if present.
     *
     * @param playerUuid target player UUID
     * @return optional record
     */
    Optional<PlayerAntiAbuseRecord> findRecord(PlayerUuid playerUuid);

    /**
     * Persists or updates the anti-abuse record for a player.
     *
     * @param record target record to persist
     */
    void saveRecord(PlayerAntiAbuseRecord record);

    /**
     * Loads the quarantine record for an island, if present.
     *
     * @param islandId target island ID
     * @return optional quarantine record
     */
    Optional<IslandQuarantineRecord> findQuarantine(IslandId islandId);

    /**
     * Persists or updates an island quarantine record.
     *
     * @param record target quarantine record to persist
     */
    void saveQuarantine(IslandQuarantineRecord record);

    /**
     * Removes the quarantine record for an island.
     *
     * @param islandId target island ID
     */
    void deleteQuarantine(IslandId islandId);

    /**
     * Loads all active island quarantines that expire at or after the specified timestamp.
     *
     * @param now reference timestamp
     * @return map of island ID to active quarantine record
     */
    Map<IslandId, IslandQuarantineRecord> loadActiveQuarantines(Instant now);
}
