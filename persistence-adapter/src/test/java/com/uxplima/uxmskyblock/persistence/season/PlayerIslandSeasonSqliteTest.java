package com.uxplima.uxmskyblock.persistence.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonPayoutState;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import com.uxplima.uxmskyblock.core.domain.season.SeasonState;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandSeasonSqliteTest {

    private Database database;
    private PlayerIslandSeasonAdapter adapter;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerIslandSeasonAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("saveSeason inserts and updates season records correctly")
    void saveAndFindSeasonRoundTrip() {
        SeasonId seasonId = SeasonId.of(1);
        SeasonRecord season = new SeasonRecord(
                seasonId, "Season 1 - Genesis", now, now.plus(30, ChronoUnit.DAYS), SeasonState.ACTIVE);

        adapter.saveSeason(season);

        Optional<SeasonRecord> found = adapter.findSeason(seasonId);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(seasonId);
        assertThat(found.get().name()).isEqualTo("Season 1 - Genesis");
        assertThat(found.get().state()).isEqualTo(SeasonState.ACTIVE);

        Optional<SeasonRecord> active = adapter.findActiveSeason();
        assertThat(active).isPresent();
        assertThat(active.get().id()).isEqualTo(seasonId);

        // Update to FROZEN
        SeasonRecord updated = new SeasonRecord(
                seasonId, "Season 1 - Genesis", now, now.plus(30, ChronoUnit.DAYS), SeasonState.FROZEN);
        adapter.saveSeason(updated);

        Optional<SeasonRecord> frozen = adapter.findSeason(seasonId);
        assertThat(frozen).isPresent();
        assertThat(frozen.get().state()).isEqualTo(SeasonState.FROZEN);
        assertThat(adapter.findActiveSeason()).isEmpty();
    }

    @Test
    @DisplayName("listSeasons returns ordered list of seasons")
    void listSeasonsReturnsOrdered() {
        adapter.saveSeason(new SeasonRecord(SeasonId.of(1), "S1", now, now.plusSeconds(3600), SeasonState.COMPLETED));
        adapter.saveSeason(new SeasonRecord(SeasonId.of(2), "S2", now, now.plusSeconds(7200), SeasonState.ACTIVE));

        List<SeasonRecord> seasons = adapter.listSeasons();
        assertThat(seasons).hasSize(2);
        assertThat(seasons.get(0).id().number()).isEqualTo(1);
        assertThat(seasons.get(1).id().number()).isEqualTo(2);
    }

    @Test
    @DisplayName("saveSnapshots and findSnapshots persist and query placements accurately")
    void saveAndFindSnapshots() {
        SeasonId s1 = SeasonId.of(1);
        adapter.saveSeason(new SeasonRecord(s1, "S1", now, now.plusSeconds(3600), SeasonState.ACTIVE));

        IslandId islandA = new IslandId(UUID.randomUUID());
        IslandId islandB = new IslandId(UUID.randomUUID());
        PlayerUuid ownerA = new PlayerUuid(UUID.randomUUID());
        PlayerUuid ownerB = new PlayerUuid(UUID.randomUUID());

        SeasonSnapshotEntry snap1 = new SeasonSnapshotEntry(s1, SeasonMetric.LEVEL, 1, islandA, ownerA, 5000L, now);
        SeasonSnapshotEntry snap2 = new SeasonSnapshotEntry(s1, SeasonMetric.LEVEL, 2, islandB, ownerB, 4000L, now);
        SeasonSnapshotEntry snap3 = new SeasonSnapshotEntry(s1, SeasonMetric.WORTH, 1, islandB, ownerB, 999999L, now);

        adapter.saveSnapshots(List.of(snap1, snap2, snap3));

        List<SeasonSnapshotEntry> levelSnaps = adapter.findSnapshots(s1, SeasonMetric.LEVEL, 10);
        assertThat(levelSnaps).hasSize(2);
        assertThat(levelSnaps.get(0).rank()).isEqualTo(1);
        assertThat(levelSnaps.get(0).islandId()).isEqualTo(islandA);
        assertThat(levelSnaps.get(0).score()).isEqualTo(5000L);
        assertThat(levelSnaps.get(1).rank()).isEqualTo(2);
        assertThat(levelSnaps.get(1).islandId()).isEqualTo(islandB);

        List<SeasonSnapshotEntry> worthSnaps = adapter.findSnapshots(s1, SeasonMetric.WORTH, 1);
        assertThat(worthSnaps).hasSize(1);
        assertThat(worthSnaps.get(0).islandId()).isEqualTo(islandB);
        assertThat(worthSnaps.get(0).score()).isEqualTo(999999L);
    }

    @Test
    @DisplayName("queuePayout, findPendingPayouts, and markPayoutDispatched manage lifecycle cleanly")
    void payoutLifecycle() {
        SeasonId s1 = SeasonId.of(1);
        adapter.saveSeason(new SeasonRecord(s1, "S1", now, now.plusSeconds(3600), SeasonState.ACTIVE));

        PlayerUuid recipient = new PlayerUuid(UUID.randomUUID());
        String payoutId1 = UUID.randomUUID().toString();
        String payoutId2 = UUID.randomUUID().toString();

        SeasonPayoutRecord p1 = new SeasonPayoutRecord(
                payoutId1, s1, recipient, "eco give %player% 50000", SeasonPayoutState.PENDING, now, null);
        SeasonPayoutRecord p2 = new SeasonPayoutRecord(
                payoutId2, s1, recipient, "crate give %player% mythic 3", SeasonPayoutState.PENDING, now, null);

        adapter.queuePayout(p1);
        adapter.queuePayout(p2);

        List<SeasonPayoutRecord> pending = adapter.findPendingPayouts(recipient);
        assertThat(pending).hasSize(2);
        assertThat(pending.stream().map(SeasonPayoutRecord::payoutId).toList()).containsExactly(payoutId1, payoutId2);

        Instant dispatchTime = now.plusSeconds(5);
        adapter.markPayoutDispatched(payoutId1, dispatchTime);

        List<SeasonPayoutRecord> remainingPending = adapter.findPendingPayouts(recipient);
        assertThat(remainingPending).hasSize(1);
        assertThat(remainingPending.get(0).payoutId()).isEqualTo(payoutId2);
    }
}
