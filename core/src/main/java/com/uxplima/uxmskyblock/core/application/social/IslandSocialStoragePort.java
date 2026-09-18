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

    void setGuestbookPinned(String reviewId, boolean pinned);

    void setGuestbookHidden(String reviewId, boolean hidden);

    void deleteGuestbookEntry(String reviewId);

    void recordVisit(SocialSubjectRef subject, ProfileId visitorProfileId, Instant visitTime);

    Optional<SubjectVisit> findVisit(SocialSubjectRef subject, ProfileId visitorProfileId);

    void addBookmark(ProfileId profileId, SocialSubjectRef subject);

    void removeBookmark(ProfileId profileId, SocialSubjectRef subject);

    boolean isBookmarked(ProfileId profileId, SocialSubjectRef subject);

    List<SocialSubjectRef> listBookmarks(ProfileId profileId);
}
