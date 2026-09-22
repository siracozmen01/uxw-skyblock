package com.uxplima.uxmskyblock.core.application.social;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookPinnedLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.social.RatingPolicy;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SelfRatingNotAllowedException;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;
import org.jspecify.annotations.Nullable;

/**
 * Application service orchestrating social ratings, Bayesian aggregation,
 * interactive paginated guestbooks, and anti-abuse safeguards.
 */
public final class IslandSocialService {

    public static final int DEFAULT_MAX_PINNED = 3;
    public static final int DEFAULT_MAX_MESSAGE_LENGTH = 256;
    public static final int DEFAULT_PRIOR_WEIGHT = 5;
    public static final double DEFAULT_PRIOR_MEAN = 3.0;

    private final IslandSocialStoragePort storage;
    private final RatingPolicy ratingPolicy;
    private final @Nullable IslandStoragePort islandStorage;
    private final Duration minDwellTime;
    private final int priorWeight;
    private final double priorMean;
    private final int maxPinnedEntries;
    private final int maxMessageLength;

    public IslandSocialService(
            IslandSocialStoragePort storage,
            RatingPolicy ratingPolicy,
            @Nullable IslandStoragePort islandStorage,
            Duration minDwellTime,
            int priorWeight,
            double priorMean,
            int maxPinnedEntries,
            int maxMessageLength) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.ratingPolicy = Objects.requireNonNull(ratingPolicy, "ratingPolicy must not be null");
        this.islandStorage = islandStorage;
        this.minDwellTime = Objects.requireNonNull(minDwellTime, "minDwellTime must not be null");
        this.priorWeight = priorWeight;
        this.priorMean = priorMean;
        this.maxPinnedEntries = maxPinnedEntries;
        this.maxMessageLength = maxMessageLength;
    }

    public IslandSocialService(
            IslandSocialStoragePort storage, RatingPolicy ratingPolicy, @Nullable IslandStoragePort islandStorage) {
        this(
                storage,
                ratingPolicy,
                islandStorage,
                Duration.ofSeconds(30),
                DEFAULT_PRIOR_WEIGHT,
                DEFAULT_PRIOR_MEAN,
                DEFAULT_MAX_PINNED,
                DEFAULT_MAX_MESSAGE_LENGTH);
    }

    public void rate(SocialSubjectRef subject, ProfileId raterProfileId, int score, Instant now) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(raterProfileId, "raterProfileId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        // 1. Validate rating score
        ratingPolicy.validateScore(score);

        // 2. Prevent self-rating & member rating on islands
        if (SocialSubjectRef.ISLAND_TYPE.equals(subject.typeId()) && islandStorage != null) {
            try {
                IslandId islandId = IslandId.of(UUID.fromString(subject.key()));
                Optional<Island> optIsland = islandStorage.findIslandById(islandId);
                if (optIsland.isPresent()) {
                    Island island = optIsland.get();
                    if (island.ownerProfileId().equals(raterProfileId) || island.isMember(raterProfileId)) {
                        throw new SelfRatingNotAllowedException(
                                "Players cannot rate their own island or an island where they are a member");
                    }
                }
            } catch (IllegalArgumentException expected) {
                // Not a valid UUID, proceed
            }
        }

        // 3. Verify minimum dwell time
        if (!minDwellTime.isZero() && !minDwellTime.isNegative()) {
            Optional<SubjectVisit> visitOpt = storage.findVisit(subject, raterProfileId);
            if (visitOpt.isEmpty()) {
                throw new IllegalStateException(
                        "Visitor has not met the minimum dwell time requirement of " + minDwellTime);
            }
            Duration dwellDuration = Duration.between(visitOpt.get().firstVisitedAt(), now);
            if (dwellDuration.compareTo(minDwellTime) < 0) {
                throw new IllegalStateException(
                        "Visitor has not met the minimum dwell time requirement of " + minDwellTime);
            }
        }

        // 4. Persist or update rating idempotently
        storage.saveRating(new SocialRating(subject, raterProfileId, score, now, now));
    }

    public Optional<SocialRating> findRating(SocialSubjectRef subject, ProfileId raterProfileId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(raterProfileId, "raterProfileId must not be null");
        return storage.findRating(subject, raterProfileId);
    }

    public RatingSummary getRatingSummary(SocialSubjectRef subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return storage.calculateSummary(subject, priorWeight, priorMean);
    }

    public String signGuestbook(SocialSubjectRef subject, ProfileId authorProfileId, String message, Instant now) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(authorProfileId, "authorProfileId must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(now, "now must not be null");

        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Guestbook message cannot be empty");
        }
        if (trimmed.length() > maxMessageLength) {
            throw new IllegalArgumentException("Guestbook message length (" + trimmed.length()
                    + ") exceeds maximum limit (" + maxMessageLength + ")");
        }

        String reviewId = UUID.randomUUID().toString();
        GuestbookEntry entry = new GuestbookEntry(reviewId, subject, authorProfileId, trimmed, false, false, now);
        storage.saveGuestbookEntry(entry);
        return reviewId;
    }

    /**
     * Pins one of this subject's entries to the top of its guestbook.
     *
     * <p>The count and the write used to be two calls with nothing between them, so two owners
     * pinning at once both read the same count and both pinned: an operator's limit of three let
     * four through. The database decides now, in one statement.
     *
     * @throws GuestbookPinnedLimitExceededException when the subject is already at its limit
     */
    public void pinGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        if (!storage.pinGuestbookEntryWithin(subject, reviewId, maxPinnedEntries)) {
            throw new GuestbookPinnedLimitExceededException(
                    "Cannot pin more than " + maxPinnedEntries + " guestbook entries");
        }
    }

    /**
     * Unpins one of this subject's entries.
     *
     * <p>The subject travels with the review id everywhere here. A review id on its own is a key to
     * every guestbook on the server, and an owner moderating their own island must not reach
     * another island's page by typing an id they read somewhere else.
     *
     * @return true when an entry changed
     */
    public boolean unpinGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return storage.unpinGuestbookEntry(subject, reviewId);
    }

    /**
     * Hides or shows one of this subject's entries.
     *
     * @return true when an entry changed
     */
    public boolean hideGuestbookEntry(SocialSubjectRef subject, String reviewId, boolean hidden) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return storage.setGuestbookHidden(subject, reviewId, hidden);
    }

    /**
     * Deletes one of this subject's entries.
     *
     * @return true when an entry was deleted
     */
    public boolean deleteGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        return storage.deleteGuestbookEntry(subject, reviewId);
    }

    /** How many entries one guestbook may keep pinned. The operator sets it. */
    public int maxPinnedEntries() {
        return maxPinnedEntries;
    }

    public List<GuestbookEntry> listGuestbookEntries(
            SocialSubjectRef subject, boolean includeHidden, int limit, int offset) {
        Objects.requireNonNull(subject, "subject must not be null");
        return storage.findGuestbookEntries(subject, includeHidden, limit, offset);
    }

    public void recordVisit(SocialSubjectRef subject, ProfileId visitorProfileId, Instant now) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(visitorProfileId, "visitorProfileId must not be null");
        Objects.requireNonNull(now, "now must not be null");
        storage.recordVisit(subject, visitorProfileId, now);
    }

    public boolean toggleBookmark(ProfileId profileId, SocialSubjectRef subject) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        if (storage.isBookmarked(profileId, subject)) {
            storage.removeBookmark(profileId, subject);
            return false;
        } else {
            storage.addBookmark(profileId, subject);
            return true;
        }
    }

    public List<SocialSubjectRef> listBookmarks(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        return storage.listBookmarks(profileId);
    }
}
