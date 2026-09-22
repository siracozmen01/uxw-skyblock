package com.uxplima.uxmskyblock.persistence.social;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;

/**
 * The social storage port, made of the three subjects it actually covers.
 *
 * <p>One class held ratings, guestbooks, visits and bookmarks: four tables and four unrelated
 * rules, 596 lines with nothing in common but the word social. The port stays one thing because
 * the application asks one question of it. What answers the question is now three.
 */
public final class PlayerIslandSocialAdapter implements IslandSocialStoragePort {

    private final SqlSocialRatings ratings;
    private final SqlSocialGuestbook guestbook;
    private final SqlSocialVisits visits;

    public PlayerIslandSocialAdapter(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        this.ratings = new SqlSocialRatings(database);
        this.guestbook = new SqlSocialGuestbook(database);
        this.visits = new SqlSocialVisits(database);
    }

    @Override
    public void saveRating(SocialRating rating) {
        ratings.saveRating(rating);
    }

    @Override
    public Optional<SocialRating> findRating(SocialSubjectRef subject, ProfileId raterProfileId) {
        return ratings.findRating(subject, raterProfileId);
    }

    @Override
    public List<SocialRating> listRatings(SocialSubjectRef subject) {
        return ratings.listRatings(subject);
    }

    @Override
    public RatingSummary calculateSummary(SocialSubjectRef subject, int priorWeight, double priorMean) {
        return ratings.calculateSummary(subject, priorWeight, priorMean);
    }

    @Override
    public void saveGuestbookEntry(GuestbookEntry entry) {
        guestbook.saveGuestbookEntry(entry);
    }

    @Override
    public Optional<GuestbookEntry> findGuestbookEntry(String reviewId) {
        return guestbook.findGuestbookEntry(reviewId);
    }

    @Override
    public List<GuestbookEntry> findGuestbookEntries(
            SocialSubjectRef subject, boolean includeHidden, int limit, int offset) {
        return guestbook.findGuestbookEntries(subject, includeHidden, limit, offset);
    }

    @Override
    public int countPinnedEntries(SocialSubjectRef subject) {
        return guestbook.countPinnedEntries(subject);
    }

    @Override
    public boolean pinGuestbookEntryWithin(SocialSubjectRef subject, String reviewId, int maxPinned) {
        return guestbook.pinGuestbookEntryWithin(subject, reviewId, maxPinned);
    }

    @Override
    public boolean unpinGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        return guestbook.unpinGuestbookEntry(subject, reviewId);
    }

    @Override
    public boolean setGuestbookHidden(SocialSubjectRef subject, String reviewId, boolean hidden) {
        return guestbook.setGuestbookHidden(subject, reviewId, hidden);
    }

    @Override
    public boolean deleteGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        return guestbook.deleteGuestbookEntry(subject, reviewId);
    }

    @Override
    public void recordVisit(SocialSubjectRef subject, ProfileId visitorProfileId, Instant visitTime) {
        visits.recordVisit(subject, visitorProfileId, visitTime);
    }

    @Override
    public Optional<SubjectVisit> findVisit(SocialSubjectRef subject, ProfileId visitorProfileId) {
        return visits.findVisit(subject, visitorProfileId);
    }

    @Override
    public void addBookmark(ProfileId profileId, SocialSubjectRef subject) {
        visits.addBookmark(profileId, subject);
    }

    @Override
    public void removeBookmark(ProfileId profileId, SocialSubjectRef subject) {
        visits.removeBookmark(profileId, subject);
    }

    @Override
    public boolean isBookmarked(ProfileId profileId, SocialSubjectRef subject) {
        return visits.isBookmarked(profileId, subject);
    }

    @Override
    public List<SocialSubjectRef> listBookmarks(ProfileId profileId) {
        return visits.listBookmarks(profileId);
    }
}
