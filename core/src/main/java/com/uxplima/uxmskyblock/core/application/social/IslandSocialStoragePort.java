package com.uxplima.uxmskyblock.core.application.social;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;

/**
 * Outbound persistence port for social ratings, guestbook reviews, visits, and bookmarks.
 */
public interface IslandSocialStoragePort {

    void saveRating(SocialRating rating);

    Optional<SocialRating> findRating(SocialSubjectRef subject, ProfileId raterProfileId);

    List<SocialRating> listRatings(SocialSubjectRef subject);

    RatingSummary calculateSummary(SocialSubjectRef subject, int priorWeight, double priorMean);

    void saveGuestbookEntry(GuestbookEntry entry);

    Optional<GuestbookEntry> findGuestbookEntry(String reviewId);

    List<GuestbookEntry> findGuestbookEntries(SocialSubjectRef subject, boolean includeHidden, int limit, int offset);

    int countPinnedEntries(SocialSubjectRef subject);

    /**
     * Pins one of a subject's entries, and only while that subject is under {@code maxPinned}.
     *
     * <p>The count and the write used to be two calls with nothing between them, so two owners
     * pinning at once both read the same count and both pinned: a limit of three let four through.
     * The count is part of the statement now, and the database is the one that says no.
     *
     * @return true when this call pinned it, false when the subject is already at its limit, the
     *     entry is already pinned, or the entry does not belong to that subject
     */
    boolean pinGuestbookEntryWithin(SocialSubjectRef subject, String reviewId, int maxPinned);

    /**
     * Unpins one of a subject's entries.
     *
     * <p>The subject is part of the statement. A review id on its own is a key to every guestbook
     * on the server, and an owner moderating their own island must not be able to reach another.
     *
     * @return true when a row changed
     */
    boolean unpinGuestbookEntry(SocialSubjectRef subject, String reviewId);

    /**
     * Hides or shows one of a subject's entries.
     *
     * @return true when a row changed
     */
    boolean setGuestbookHidden(SocialSubjectRef subject, String reviewId, boolean hidden);

    /**
     * Deletes one of a subject's entries.
     *
     * @return true when a row was deleted
     */
    boolean deleteGuestbookEntry(SocialSubjectRef subject, String reviewId);

    void recordVisit(SocialSubjectRef subject, ProfileId visitorProfileId, Instant visitTime);

    Optional<SubjectVisit> findVisit(SocialSubjectRef subject, ProfileId visitorProfileId);

    void addBookmark(ProfileId profileId, SocialSubjectRef subject);

    void removeBookmark(ProfileId profileId, SocialSubjectRef subject);

    boolean isBookmarked(ProfileId profileId, SocialSubjectRef subject);

    List<SocialSubjectRef> listBookmarks(ProfileId profileId);
}
