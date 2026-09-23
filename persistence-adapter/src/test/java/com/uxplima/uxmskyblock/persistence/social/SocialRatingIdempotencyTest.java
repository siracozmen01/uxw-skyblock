package com.uxplima.uxmskyblock.persistence.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rating a subject again changes the one rating a player has, and nothing else.
 *
 * <p>The game mode architecture names this test: a changed rating updates the existing record
 * without corrupting the aggregate. The race half runs on MariaDB and PostgreSQL in
 * {@link SocialRatingIdempotencyIntegrationTest}.
 */
class SocialRatingIdempotencyTest {

    private Database database;

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("A second rating replaces the first, keeps when it was first given, and counts once")
    void aChangedRatingReplacesTheOld() {
        aChangedRatingReplacesTheOld(database);
    }

    static void aChangedRatingReplacesTheOld(Database database) {
        PlayerIslandSocialAdapter social = new PlayerIslandSocialAdapter(database);
        SocialSubjectRef island = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));
        ProfileId rater = ProfileId.of(UUID.randomUUID());
        ProfileId other = ProfileId.of(UUID.randomUUID());
        Instant first = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(60);
        Instant later = first.plusSeconds(30);

        social.saveRating(new SocialRating(island, rater, 1, first, first));
        social.saveRating(new SocialRating(island, other, 5, first, first));
        social.saveRating(new SocialRating(island, rater, 5, later, later));
        social.saveRating(new SocialRating(island, rater, 5, later, later));

        SocialRating kept = social.findRating(island, rater).orElseThrow();
        assertThat(kept.score()).isEqualTo(5);
        assertThat(kept.createdAt()).describedAs("when it was first given").isEqualTo(first);
        RatingSummary summary = social.calculateSummary(island, 0, 3.0);
        assertThat(summary.totalRatings()).describedAs("one rating per player").isEqualTo(2);
        assertThat(summary.averageScore()).isEqualTo(5.0);
    }

    /**
     * Eight first ratings from one player at once, the way a double click on a slow server arrives.
     * None may fail, and the subject ends with one rating from that player.
     */
    static void racingFirstRatingsLeaveOne(Database database) throws Exception {
        PlayerIslandSocialAdapter social = new PlayerIslandSocialAdapter(database);
        SocialSubjectRef island = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));
        ProfileId rater = ProfileId.of(UUID.randomUUID());
        Instant now = Instant.now();
        int racers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<?>> racing = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < racers; i++) {
                int score = 1 + (i % 5);
                racing.add(pool.submit(() -> {
                    start.await();
                    social.saveRating(new SocialRating(island, rater, score, now, now));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> each : racing) {
                each.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(social.calculateSummary(island, 0, 3.0).totalRatings())
                .describedAs("one rating from the one player")
                .isEqualTo(1);
    }
}
