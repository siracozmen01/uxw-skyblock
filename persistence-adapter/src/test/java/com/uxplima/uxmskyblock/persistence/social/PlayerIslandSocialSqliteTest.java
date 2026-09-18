package com.uxplima.uxmskyblock.persistence.social;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIslandSocialSqliteTest {

    private Database database;
    private PlayerIslandSocialAdapter adapter;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final SocialSubjectRef subject = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));
    private final ProfileId profile1 = ProfileId.of(UUID.randomUUID());
    private final ProfileId profile2 = ProfileId.of(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        database = DatabaseTestFixture.createSqliteInMemory();
        MigrationRunner runner = new MigrationRunner(database);
        runner.apply(SkyblockMigrations.getMigrations(database.dialect()));
        adapter = new PlayerIslandSocialAdapter(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null && !database.isClosed()) {
            database.close();
        }
    }

    @Test
    @DisplayName("Ratings round-trip: save, find, update, and list")
    void ratingsRoundTrip() {
        SocialRating r1 = new SocialRating(subject, profile1, 5, now, now);
        adapter.saveRating(r1);

        Optional<SocialRating> found = adapter.findRating(subject, profile1);
        assertThat(found).isPresent();
        assertThat(found.get().score()).isEqualTo(5);
        assertThat(found.get().raterProfileId()).isEqualTo(profile1);

        // Update score from 5 to 4
        Instant updatedTime = now.plus(1, ChronoUnit.HOURS);
        SocialRating r1Updated = new SocialRating(subject, profile1, 4, now, updatedTime);
        adapter.saveRating(r1Updated);

        Optional<SocialRating> foundUpdated = adapter.findRating(subject, profile1);
        assertThat(foundUpdated).isPresent();
        assertThat(foundUpdated.get().score()).isEqualTo(4);

        // Add 2nd rating
        SocialRating r2 = new SocialRating(subject, profile2, 5, now, now);
        adapter.saveRating(r2);

        List<SocialRating> list = adapter.listRatings(subject);
        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("Rating summary calculates raw mean and Bayesian weighted score accurately")
    void ratingSummaryCalculation() {
        // Empty summary
        RatingSummary empty = adapter.calculateSummary(subject, 5, 3.0);
        assertThat(empty.totalRatings()).isEqualTo(0);
        assertThat(empty.averageScore()).isEqualTo(0.0);
        assertThat(empty.bayesianScore()).isEqualTo(0.0);

        // 1 rating of 5: raw = 5.0, bayesian = (5*3.0 + 5) / (5 + 1) = 20 / 6 = 3.3333...
        adapter.saveRating(new SocialRating(subject, profile1, 5, now, now));
        RatingSummary summary1 = adapter.calculateSummary(subject, 5, 3.0);
        assertThat(summary1.totalRatings()).isEqualTo(1);
        assertThat(summary1.averageScore()).isEqualTo(5.0);
        assertThat(summary1.bayesianScore()).isCloseTo(3.3333, Offset.offset(0.001));

        // 2nd rating of 5: raw = 5.0, bayesian = (5*3.0 + 10) / (5 + 2) = 25 / 7 = 3.5714...
        adapter.saveRating(new SocialRating(subject, profile2, 5, now, now));
        RatingSummary summary2 = adapter.calculateSummary(subject, 5, 3.0);
        assertThat(summary2.totalRatings()).isEqualTo(2);
        assertThat(summary2.averageScore()).isEqualTo(5.0);
        assertThat(summary2.bayesianScore()).isCloseTo(3.5714, Offset.offset(0.001));
    }

    @Test
    @DisplayName("Guestbook reviews: CRUD, pagination, hidden filter, pinning, and counting")
    void guestbookLifecycle() {
        GuestbookEntry e1 = new GuestbookEntry("rev-1", subject, profile1, "Amazing island!", false, false, now);
        GuestbookEntry e2 = new GuestbookEntry(
                "rev-2", subject, profile2, "Loved the farm design", false, false, now.plusSeconds(10));
        adapter.saveGuestbookEntry(e1);
        adapter.saveGuestbookEntry(e2);

        Optional<GuestbookEntry> found = adapter.findGuestbookEntry("rev-1");
        assertThat(found).isPresent();
        assertThat(found.get().message()).isEqualTo("Amazing island!");
        assertThat(found.get().isPinned()).isFalse();

        // Pin e1
        adapter.setGuestbookPinned("rev-1", true);
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(1);
        assertThat(adapter.findGuestbookEntry("rev-1").get().isPinned()).isTrue();

        // Hide e2
        adapter.setGuestbookHidden("rev-2", true);
        assertThat(adapter.findGuestbookEntry("rev-2").get().isHidden()).isTrue();

        // Query without hidden: only rev-1 returned
        List<GuestbookEntry> publicEntries = adapter.findGuestbookEntries(subject, false, 10, 0);
        assertThat(publicEntries).hasSize(1);
        assertThat(publicEntries.get(0).reviewId()).isEqualTo("rev-1");

        // Query with hidden: both returned
        List<GuestbookEntry> allEntries = adapter.findGuestbookEntries(subject, true, 10, 0);
        assertThat(allEntries).hasSize(2);

        // Delete rev-1
        adapter.deleteGuestbookEntry("rev-1");
        assertThat(adapter.findGuestbookEntry("rev-1")).isEmpty();
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(0);
    }

    @Test
    @DisplayName("Subject visits: first visit inserts, subsequent visit increments count and updates timestamp")
    void subjectVisitsTracking() {
        adapter.recordVisit(subject, profile1, now);

        Optional<SubjectVisit> visit1 = adapter.findVisit(subject, profile1);
        assertThat(visit1).isPresent();
        assertThat(visit1.get().visitCount()).isEqualTo(1);
        assertThat(visit1.get().firstVisitedAt()).isEqualTo(now);
        assertThat(visit1.get().lastVisitedAt()).isEqualTo(now);

        // Second visit after 2 hours
        Instant later = now.plus(2, ChronoUnit.HOURS);
        adapter.recordVisit(subject, profile1, later);

        Optional<SubjectVisit> visit2 = adapter.findVisit(subject, profile1);
        assertThat(visit2).isPresent();
        assertThat(visit2.get().visitCount()).isEqualTo(2);
        assertThat(visit2.get().firstVisitedAt()).isEqualTo(now);
        assertThat(visit2.get().lastVisitedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("Bookmarks: add, query, remove, and list")
    void bookmarksLifecycle() {
        assertThat(adapter.isBookmarked(profile1, subject)).isFalse();

        adapter.addBookmark(profile1, subject);
        assertThat(adapter.isBookmarked(profile1, subject)).isTrue();

        SocialSubjectRef subject2 = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));
        adapter.addBookmark(profile1, subject2);

        List<SocialSubjectRef> bookmarks = adapter.listBookmarks(profile1);
        assertThat(bookmarks).containsExactlyInAnyOrder(subject, subject2);

        // Remove bookmark
        adapter.removeBookmark(profile1, subject);
        assertThat(adapter.isBookmarked(profile1, subject)).isFalse();
        assertThat(adapter.listBookmarks(profile1)).containsExactly(subject2);
    }
}
