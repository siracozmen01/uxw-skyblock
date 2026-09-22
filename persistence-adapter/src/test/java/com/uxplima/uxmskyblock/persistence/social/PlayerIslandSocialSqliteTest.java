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

        // Pin e1, under a limit the statement itself enforces
        assertThat(adapter.pinGuestbookEntryWithin(subject, "rev-1", 3)).isTrue();
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(1);
        assertThat(adapter.findGuestbookEntry("rev-1").get().isPinned()).isTrue();

        // Hide e2
        assertThat(adapter.setGuestbookHidden(subject, "rev-2", true)).isTrue();
        assertThat(adapter.findGuestbookEntry("rev-2").get().isHidden()).isTrue();

        // Query without hidden: only rev-1 returned
        List<GuestbookEntry> publicEntries = adapter.findGuestbookEntries(subject, false, 10, 0);
        assertThat(publicEntries).hasSize(1);
        assertThat(publicEntries.get(0).reviewId()).isEqualTo("rev-1");

        // Query with hidden: both returned
        List<GuestbookEntry> allEntries = adapter.findGuestbookEntries(subject, true, 10, 0);
        assertThat(allEntries).hasSize(2);

        // Delete rev-1
        assertThat(adapter.deleteGuestbookEntry(subject, "rev-1")).isTrue();
        assertThat(adapter.findGuestbookEntry("rev-1")).isEmpty();
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(0);
    }

    @Test
    @DisplayName("The pin limit is the statement's, so two pins at once cannot both get through")
    void thePinLimitHolds() {
        for (int i = 1; i <= 6; i++) {
            adapter.saveGuestbookEntry(new GuestbookEntry(
                    "pin-" + i, subject, profile1, "Message " + i, false, false, now.plusSeconds(i)));
        }

        int pinned = 0;
        for (int i = 1; i <= 6; i++) {
            if (adapter.pinGuestbookEntryWithin(subject, "pin-" + i, 3)) {
                pinned++;
            }
        }

        assertThat(pinned).describedAs("three go on, three are refused").isEqualTo(3);
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(3);
    }

    @Test
    @DisplayName("Six owners pinning at once still leave three pinned, never four")
    void thePinLimitHoldsUnderConcurrency() throws Exception {
        for (int i = 1; i <= 6; i++) {
            adapter.saveGuestbookEntry(new GuestbookEntry(
                    "race-" + i, subject, profile1, "Message " + i, false, false, now.plusSeconds(i)));
        }

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(6);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String reviewId = "race-" + i;
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    if (adapter.pinGuestbookEntryWithin(subject, reviewId, 3)) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ignored) {
                    // A database that refuses the write under contention is a refusal, not a pin.
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        go.countDown();
        assertThat(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .describedAs("every pin finished")
                .isTrue();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(adapter.countPinnedEntries(subject))
                .describedAs("the operator said three, and three is what the page holds")
                .isEqualTo(3);
        assertThat(accepted.get())
                .describedAs("as many pins reported as there are pinned entries")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Pinning what is already pinned changes nothing and does not spend a slot")
    void pinningTwiceSpendsNothing() {
        adapter.saveGuestbookEntry(new GuestbookEntry("pin-a", subject, profile1, "Once", false, false, now));

        assertThat(adapter.pinGuestbookEntryWithin(subject, "pin-a", 1)).isTrue();
        assertThat(adapter.pinGuestbookEntryWithin(subject, "pin-a", 1))
                .describedAs("already pinned, so nothing to do")
                .isFalse();
        assertThat(adapter.countPinnedEntries(subject)).isEqualTo(1);
    }

    @Test
    @DisplayName("One island cannot moderate another island's page by naming its entry")
    void oneIslandCannotReachAnother() {
        SocialSubjectRef other = SocialSubjectRef.island(IslandId.of(UUID.randomUUID()));
        adapter.saveGuestbookEntry(new GuestbookEntry("theirs", subject, profile1, "Mine", false, false, now));

        assertThat(adapter.pinGuestbookEntryWithin(other, "theirs", 3))
                .describedAs("a review id on its own is a key to every guestbook on the server")
                .isFalse();
        assertThat(adapter.setGuestbookHidden(other, "theirs", true)).isFalse();
        assertThat(adapter.unpinGuestbookEntry(other, "theirs")).isFalse();
        assertThat(adapter.deleteGuestbookEntry(other, "theirs")).isFalse();

        assertThat(adapter.findGuestbookEntry("theirs")).isPresent();
        assertThat(adapter.findGuestbookEntry("theirs").orElseThrow().isHidden())
                .isFalse();
    }

    @Test
    @DisplayName("An entry that is not there reports that it is not there")
    void anUnknownEntryReportsItself() {
        assertThat(adapter.pinGuestbookEntryWithin(subject, "nothing", 3)).isFalse();
        assertThat(adapter.unpinGuestbookEntry(subject, "nothing")).isFalse();
        assertThat(adapter.setGuestbookHidden(subject, "nothing", true)).isFalse();
        assertThat(adapter.deleteGuestbookEntry(subject, "nothing")).isFalse();
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
