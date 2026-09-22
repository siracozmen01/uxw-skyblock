package com.uxplima.uxmskyblock.core.application.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookPinnedLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SelfRatingNotAllowedException;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandSocialServiceTest {

    private InMemorySocialStorage storage;
    private InMemoryIslandStorage islandStorage;
    private IslandSocialService service;

    private final IslandId islandId = new IslandId(UUID.randomUUID());
    private final ProfileId ownerProfileId = new ProfileId(UUID.randomUUID());
    private final PlayerUuid ownerAccountUuid = new PlayerUuid(UUID.randomUUID());
    private final ProfileId visitorProfileId = new ProfileId(UUID.randomUUID());
    private final SocialSubjectRef islandSubject = SocialSubjectRef.island(islandId);
    private final Instant now = Instant.parse("2026-09-18T12:00:00Z");

    @BeforeEach
    void setUp() {
        storage = new InMemorySocialStorage();
        islandStorage = new InMemoryIslandStorage();

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 50);
        Island island = Island.create(islandId, bounds, ownerAccountUuid, ownerProfileId, now);
        islandStorage.save(island);

        service = new IslandSocialService(
                storage, RatingPolicy.standardFiveStar(), islandStorage, Duration.ofSeconds(30), 5, 3.0, 3, 256);
    }

    @Test
    @DisplayName("rate succeeds when dwell time met and updates rating idempotently")
    void rateSucceedsAndUpdates() {
        // Record visit 35 seconds ago
        storage.recordVisit(islandSubject, visitorProfileId, now.minusSeconds(35));

        service.rate(islandSubject, visitorProfileId, 5, now);

        Optional<SocialRating> ratingOpt = service.findRating(islandSubject, visitorProfileId);
        assertThat(ratingOpt).isPresent();
        assertThat(ratingOpt.get().score()).isEqualTo(5);

        // Update rating to 4
        service.rate(islandSubject, visitorProfileId, 4, now.plusSeconds(10));
        Optional<SocialRating> updatedOpt = service.findRating(islandSubject, visitorProfileId);
        assertThat(updatedOpt).isPresent();
        assertThat(updatedOpt.get().score()).isEqualTo(4);
    }

    @Test
    @DisplayName("rate rejects when dwell time is insufficient")
    void rateRejectsWhenDwellTimeInsufficient() {
        // Visit only 10s ago (threshold is 30s)
        storage.recordVisit(islandSubject, visitorProfileId, now.minusSeconds(10));

        assertThatThrownBy(() -> service.rate(islandSubject, visitorProfileId, 5, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimum dwell time");
    }

    @Test
    @DisplayName("rate rejects self-rating by island owner")
    void rateRejectsSelfRating() {
        storage.recordVisit(islandSubject, ownerProfileId, now.minusSeconds(60));

        assertThatThrownBy(() -> service.rate(islandSubject, ownerProfileId, 5, now))
                .isInstanceOf(SelfRatingNotAllowedException.class);
    }

    @Test
    @DisplayName("rate rejects score out of bounds")
    void rateRejectsScoreOutOfBounds() {
        storage.recordVisit(islandSubject, visitorProfileId, now.minusSeconds(60));

        assertThatThrownBy(() -> service.rate(islandSubject, visitorProfileId, 6, now))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rate(islandSubject, visitorProfileId, 0, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("signGuestbook validates message and lists entries")
    void guestbookSignAndList() {
        String id1 = service.signGuestbook(islandSubject, visitorProfileId, "Awesome island layout!", now);
        assertThat(id1).isNotBlank();

        List<GuestbookEntry> entries = service.listGuestbookEntries(islandSubject, false, 10, 0);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).message()).isEqualTo("Awesome island layout!");
    }

    @Test
    @DisplayName("pinGuestbookEntry enforces 3 pinned entries limit")
    void guestbookPinLimit() {
        String e1 = service.signGuestbook(islandSubject, visitorProfileId, "Msg 1", now);
        String e2 = service.signGuestbook(islandSubject, visitorProfileId, "Msg 2", now);
        String e3 = service.signGuestbook(islandSubject, visitorProfileId, "Msg 3", now);
        String e4 = service.signGuestbook(islandSubject, visitorProfileId, "Msg 4", now);

        service.pinGuestbookEntry(islandSubject, e1);
        service.pinGuestbookEntry(islandSubject, e2);
        service.pinGuestbookEntry(islandSubject, e3);

        assertThatThrownBy(() -> service.pinGuestbookEntry(islandSubject, e4))
                .isInstanceOf(GuestbookPinnedLimitExceededException.class);

        // Unpin e1, then pin e4 succeeds
        service.unpinGuestbookEntry(islandSubject, e1);
        service.pinGuestbookEntry(islandSubject, e4);
    }

    @Test
    @DisplayName("toggleBookmark adds and removes bookmarks")
    void bookmarkToggle() {
        boolean bookmarked = service.toggleBookmark(visitorProfileId, islandSubject);
        assertThat(bookmarked).isTrue();
        assertThat(service.listBookmarks(visitorProfileId)).containsExactly(islandSubject);

        boolean unbookmarked = service.toggleBookmark(visitorProfileId, islandSubject);
        assertThat(unbookmarked).isFalse();
        assertThat(service.listBookmarks(visitorProfileId)).isEmpty();
    }

    @Test
    @DisplayName("getRatingSummary computes Bayesian weighted score accurately")
    void ratingSummaryCalculation() {
        // priorWeight = 5, priorMean = 3.0
        // No ratings -> empty / default
        RatingSummary emptySummary = service.getRatingSummary(islandSubject);
        assertThat(emptySummary.totalRatings()).isEqualTo(0);
        assertThat(emptySummary.bayesianScore()).isEqualTo(3.0);

        // Add 2 ratings: 5 and 5
        // Sum = 10, count = 2.
        // Bayesian = (5 * 3.0 + 10) / (5 + 2) = 25 / 7 = 3.5714...
        ProfileId r1 = new ProfileId(UUID.randomUUID());
        ProfileId r2 = new ProfileId(UUID.randomUUID());
        storage.saveRating(new SocialRating(islandSubject, r1, 5, now, now));
        storage.saveRating(new SocialRating(islandSubject, r2, 5, now, now));

        RatingSummary summary = service.getRatingSummary(islandSubject);
        assertThat(summary.totalRatings()).isEqualTo(2);
        assertThat(summary.averageScore()).isEqualTo(5.0);
        assertThat(summary.bayesianScore()).isBetween(3.57, 3.58);
    }

    private static final class InMemorySocialStorage implements IslandSocialStoragePort {
        private final Map<String, SocialRating> ratings = new HashMap<>();
        private final Map<String, GuestbookEntry> guestbook = new HashMap<>();
        private final Map<String, SubjectVisit> visits = new HashMap<>();
        private final List<SocialSubjectRef> bookmarks = new ArrayList<>();

        @Override
        public void saveRating(SocialRating rating) {
            ratings.put(rating.subject().key() + ":" + rating.raterProfileId().value(), rating);
        }

        @Override
        public Optional<SocialRating> findRating(SocialSubjectRef subject, ProfileId raterProfileId) {
            return Optional.ofNullable(ratings.get(subject.key() + ":" + raterProfileId.value()));
        }

        @Override
        public List<SocialRating> listRatings(SocialSubjectRef subject) {
            return ratings.values().stream()
                    .filter(r -> r.subject().equals(subject))
                    .toList();
        }

        @Override
        public RatingSummary calculateSummary(SocialSubjectRef subject, int priorWeight, double priorMean) {
            List<SocialRating> list = listRatings(subject);
            if (list.isEmpty()) {
                return new RatingSummary(subject, 0, 0.0, priorMean);
            }
            double sum = list.stream().mapToInt(SocialRating::score).sum();
            double avg = sum / list.size();
            double bayesian = (priorWeight * priorMean + sum) / (priorWeight + list.size());
            return new RatingSummary(subject, list.size(), avg, bayesian);
        }

        @Override
        public void saveGuestbookEntry(GuestbookEntry entry) {
            guestbook.put(entry.reviewId(), entry);
        }

        @Override
        public Optional<GuestbookEntry> findGuestbookEntry(String reviewId) {
            return Optional.ofNullable(guestbook.get(reviewId));
        }

        @Override
        public List<GuestbookEntry> findGuestbookEntries(
                SocialSubjectRef subject, boolean includeHidden, int limit, int offset) {
            return guestbook.values().stream()
                    .filter(e -> e.subject().equals(subject))
                    .filter(e -> includeHidden || !e.isHidden())
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public int countPinnedEntries(SocialSubjectRef subject) {
            return (int) guestbook.values().stream()
                    .filter(e -> e.subject().equals(subject) && e.isPinned())
                    .count();
        }

        @Override
        public boolean pinGuestbookEntryWithin(SocialSubjectRef subject, String reviewId, int maxPinned) {
            GuestbookEntry entry = guestbook.get(reviewId);
            // The real store does this in one statement. The double refuses on the same conditions.
            if (entry == null || !entry.subject().equals(subject) || entry.isPinned()) {
                return false;
            }
            if (countPinnedEntries(subject) >= maxPinned) {
                return false;
            }
            setPinned(entry, true);
            return true;
        }

        @Override
        public boolean unpinGuestbookEntry(SocialSubjectRef subject, String reviewId) {
            GuestbookEntry entry = guestbook.get(reviewId);
            if (entry == null || !entry.subject().equals(subject)) {
                return false;
            }
            setPinned(entry, false);
            return true;
        }

        private void setPinned(GuestbookEntry entry, boolean pinned) {
            guestbook.put(
                    entry.reviewId(),
                    new GuestbookEntry(
                            entry.reviewId(),
                            entry.subject(),
                            entry.authorProfileId(),
                            entry.message(),
                            entry.isHidden(),
                            pinned,
                            entry.createdAt()));
        }

        @Override
        public boolean setGuestbookHidden(SocialSubjectRef subject, String reviewId, boolean hidden) {
            GuestbookEntry entry = guestbook.get(reviewId);
            if (entry == null || !entry.subject().equals(subject)) {
                return false;
            }
            guestbook.put(
                    reviewId,
                    new GuestbookEntry(
                            entry.reviewId(),
                            entry.subject(),
                            entry.authorProfileId(),
                            entry.message(),
                            hidden,
                            entry.isPinned(),
                            entry.createdAt()));
            return true;
        }

        @Override
        public boolean deleteGuestbookEntry(SocialSubjectRef subject, String reviewId) {
            GuestbookEntry entry = guestbook.get(reviewId);
            if (entry == null || !entry.subject().equals(subject)) {
                return false;
            }
            guestbook.remove(reviewId);
            return true;
        }

        @Override
        public void recordVisit(SocialSubjectRef subject, ProfileId visitorProfileId, Instant visitTime) {
            String key = subject.key() + ":" + visitorProfileId.value();
            SubjectVisit existing = visits.get(key);
            if (existing != null) {
                visits.put(
                        key,
                        new SubjectVisit(
                                subject,
                                visitorProfileId,
                                existing.visitCount() + 1,
                                existing.firstVisitedAt(),
                                visitTime));
            } else {
                visits.put(key, new SubjectVisit(subject, visitorProfileId, 1, visitTime, visitTime));
            }
        }

        @Override
        public Optional<SubjectVisit> findVisit(SocialSubjectRef subject, ProfileId visitorProfileId) {
            return Optional.ofNullable(visits.get(subject.key() + ":" + visitorProfileId.value()));
        }

        @Override
        public void addBookmark(ProfileId profileId, SocialSubjectRef subject) {
            bookmarks.add(subject);
        }

        @Override
        public void removeBookmark(ProfileId profileId, SocialSubjectRef subject) {
            bookmarks.remove(subject);
        }

        @Override
        public boolean isBookmarked(ProfileId profileId, SocialSubjectRef subject) {
            return bookmarks.contains(subject);
        }

        @Override
        public List<SocialSubjectRef> listBookmarks(ProfileId profileId) {
            return List.copyOf(bookmarks);
        }
    }

    private static final class InMemoryIslandStorage implements IslandStoragePort {
        private final Map<IslandId, Island> islands = new HashMap<>();

        void save(Island island) {
            islands.put(island.id(), island);
        }

        @Override
        public void saveIsland(Island island, com.uxplima.uxmskyblock.core.domain.island.IslandLocation location) {
            save(island);
        }

        @Override
        public Optional<Island> findIslandById(IslandId islandId) {
            return Optional.ofNullable(islands.get(islandId));
        }

        @Override
        public Optional<IslandId> findIslandIdByProfileId(ProfileId profileId) {
            return islands.values().stream()
                    .filter(i -> i.ownerProfileId().equals(profileId))
                    .map(Island::id)
                    .findFirst();
        }

        @Override
        public Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> findLocationByIslandId(
                IslandId islandId) {
            return Optional.empty();
        }

        @Override
        public void deleteIsland(IslandId islandId) {
            islands.remove(islandId);
        }
    }
}
