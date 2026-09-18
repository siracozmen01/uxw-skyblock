package com.uxplima.uxmskyblock.core.application.season;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;

/**
 * Outbound persistence port for seasons, immutable snapshots, and durable reward payouts.
 */
public interface IslandSeasonStoragePort {

    void saveSeason(SeasonRecord season);

    Optional<SeasonRecord> findActiveSeason();

    Optional<SeasonRecord> findSeason(SeasonId id);

    List<SeasonRecord> listSeasons();

    void saveSnapshots(List<SeasonSnapshotEntry> entries);

    List<SeasonSnapshotEntry> findSnapshots(SeasonId id, SeasonMetric metric, int limit);

    void queuePayout(SeasonPayoutRecord payout);

    List<SeasonPayoutRecord> findPendingPayouts(PlayerUuid recipient);

    void markPayoutDispatched(String payoutId, Instant dispatchedAt);
}
