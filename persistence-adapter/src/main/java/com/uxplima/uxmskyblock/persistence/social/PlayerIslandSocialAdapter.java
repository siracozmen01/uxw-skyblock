package com.uxplima.uxmskyblock.persistence.social;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.social.IslandSocialStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;

/**
 * Production SQL implementation of {@link IslandSocialStoragePort} managing
 * ratings, Bayesian summary calculations, interactive guestbooks, visits, and player bookmarks.
 */
public final class PlayerIslandSocialAdapter implements IslandSocialStoragePort {

    private final Database database;

    public PlayerIslandSocialAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public void saveRating(SocialRating rating) {
        Objects.requireNonNull(rating, "rating must not be null");

        String checkSql = """
                SELECT 1 FROM social_ratings
                WHERE subject_type_id = ? AND subject_key = ? AND rater_profile_id = ?
                """;
        String updateSql = """
                UPDATE social_ratings
                SET score = ?, updated_at = ?
                WHERE subject_type_id = ? AND subject_key = ? AND rater_profile_id = ?
                """;
        String insertSql = """
                INSERT INTO social_ratings (
                    subject_type_id, subject_key, rater_profile_id, score, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, rating.subject().typeId());
                checkStmt.setString(2, rating.subject().key());
                checkStmt.setString(3, rating.raterProfileId().value().toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setInt(1, rating.score());
                    updateStmt.setTimestamp(2, Timestamp.from(rating.updatedAt()));
                    updateStmt.setString(3, rating.subject().typeId());
                    updateStmt.setString(4, rating.subject().key());
                    updateStmt.setString(5, rating.raterProfileId().value().toString());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, rating.subject().typeId());
                    insertStmt.setString(2, rating.subject().key());
                    insertStmt.setString(3, rating.raterProfileId().value().toString());
                    insertStmt.setInt(4, rating.score());
                    insertStmt.setTimestamp(5, Timestamp.from(rating.createdAt()));
                    insertStmt.setTimestamp(6, Timestamp.from(rating.updatedAt()));
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException(
                    "Failed to persist rating for subject: " + rating.subject() + ", rater: " + rating.raterProfileId(),
                    e);
        }
    }

    @Override
    public Optional<SocialRating> findRating(SocialSubjectRef subject, ProfileId raterProfileId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(raterProfileId, "raterProfileId must not be null");

        String sql = """
                SELECT subject_type_id, subject_key, rater_profile_id, score, created_at, updated_at
                FROM social_ratings
                WHERE subject_type_id = ? AND subject_key = ? AND rater_profile_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, subject.typeId());
            stmt.setString(2, subject.key());
            stmt.setString(3, raterProfileId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRating(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException(
                    "Failed to find rating for subject: " + subject + ", rater: " + raterProfileId, e);
        }
    }

    @Override
    public List<SocialRating> listRatings(SocialSubjectRef subject) {
        Objects.requireNonNull(subject, "subject must not be null");

        String sql = """
                SELECT subject_type_id, subject_key, rater_profile_id, score, created_at, updated_at
                FROM social_ratings
                WHERE subject_type_id = ? AND subject_key = ?
                ORDER BY created_at DESC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, subject.typeId());
            stmt.setString(2, subject.key());

            List<SocialRating> list = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRating(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to list ratings for subject: " + subject, e);
        }
    }

    @Override
    public RatingSummary calculateSummary(SocialSubjectRef subject, int priorWeight, double priorMean) {
        Objects.requireNonNull(subject, "subject must not be null");

        String sql = """
                SELECT COUNT(*), COALESCE(AVG(score), 0.0), COALESCE(SUM(score), 0)
                FROM social_ratings
                WHERE subject_type_id = ? AND subject_key = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, subject.typeId());
            stmt.setString(2, subject.key());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int count = rs.getInt(1);
                    double avg = rs.getDouble(2);
                    long sum = rs.getLong(3);

                    if (count == 0) {
                        return RatingSummary.empty(subject);
                    }

                    double bayesian = ((priorWeight * priorMean) + sum) / (priorWeight + count);
                    return new RatingSummary(subject, count, avg, bayesian);
                }
                return RatingSummary.empty(subject);
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to calculate rating summary for subject: " + subject, e);
        }
    }

    @Override
    public void saveGuestbookEntry(GuestbookEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");

        String checkSql = "SELECT 1 FROM guestbook_reviews WHERE review_id = ?";
        String updateSql = """
                UPDATE guestbook_reviews
                SET message = ?, is_hidden = ?, is_pinned = ?
                WHERE review_id = ?
                """;
        String insertSql = """
                INSERT INTO guestbook_reviews (
                    review_id, subject_type_id, subject_key, author_profile_id, message, is_hidden, is_pinned, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, entry.reviewId());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, entry.message());
                    updateStmt.setBoolean(2, entry.isHidden());
                    updateStmt.setBoolean(3, entry.isPinned());
                    updateStmt.setString(4, entry.reviewId());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, entry.reviewId());
                    insertStmt.setString(2, entry.subject().typeId());
                    insertStmt.setString(3, entry.subject().key());
                    insertStmt.setString(4, entry.authorProfileId().value().toString());
                    insertStmt.setString(5, entry.message());
                    insertStmt.setBoolean(6, entry.isHidden());
                    insertStmt.setBoolean(7, entry.isPinned());
                    insertStmt.setTimestamp(8, Timestamp.from(entry.createdAt()));
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to persist guestbook entry: " + entry.reviewId(), e);
        }
    }

    @Override
    public Optional<GuestbookEntry> findGuestbookEntry(String reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = """
                SELECT review_id, subject_type_id, subject_key, author_profile_id, message, is_hidden, is_pinned, created_at
                FROM guestbook_reviews
                WHERE review_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, reviewId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapGuestbook(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to find guestbook entry: " + reviewId, e);
        }
    }

    @Override
    public List<GuestbookEntry> findGuestbookEntries(
            SocialSubjectRef subject, boolean includeHidden, int limit, int offset) {
        Objects.requireNonNull(subject, "subject must not be null");

        String sql = includeHidden ? """
                SELECT review_id, subject_type_id, subject_key, author_profile_id, message, is_hidden, is_pinned, created_at
                FROM guestbook_reviews
                WHERE subject_type_id = ? AND subject_key = ?
                ORDER BY is_pinned DESC, created_at DESC
                LIMIT ? OFFSET ?
                """ : """
                SELECT review_id, subject_type_id, subject_key, author_profile_id, message, is_hidden, is_pinned, created_at
                FROM guestbook_reviews
                WHERE subject_type_id = ? AND subject_key = ? AND is_hidden = ?
                ORDER BY is_pinned DESC, created_at DESC
                LIMIT ? OFFSET ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, subject.typeId());
            stmt.setString(2, subject.key());
            if (includeHidden) {
                stmt.setInt(3, limit);
                stmt.setInt(4, offset);
            } else {
                stmt.setBoolean(3, false);
                stmt.setInt(4, limit);
                stmt.setInt(5, offset);
            }

            List<GuestbookEntry> list = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapGuestbook(rs));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to list guestbook entries for subject: " + subject, e);
        }
    }

    @Override
    public int countPinnedEntries(SocialSubjectRef subject) {
        Objects.requireNonNull(subject, "subject must not be null");

        String sql = """
                SELECT COUNT(*)
                FROM guestbook_reviews
                WHERE subject_type_id = ? AND subject_key = ? AND is_pinned = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, subject.typeId());
            stmt.setString(2, subject.key());
            stmt.setBoolean(3, true);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to count pinned guestbook entries for subject: " + subject, e);
        }
    }

    @Override
    public void setGuestbookPinned(String reviewId, boolean pinned) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = "UPDATE guestbook_reviews SET is_pinned = ? WHERE review_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, pinned);
            stmt.setString(2, reviewId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to update pinned state for review: " + reviewId, e);
        }
    }

    @Override
    public void setGuestbookHidden(String reviewId, boolean hidden) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = "UPDATE guestbook_reviews SET is_hidden = ? WHERE review_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, hidden);
            stmt.setString(2, reviewId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to update hidden state for review: " + reviewId, e);
        }
    }

    @Override
    public void deleteGuestbookEntry(String reviewId) {
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = "DELETE FROM guestbook_reviews WHERE review_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, reviewId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to delete guestbook entry: " + reviewId, e);
        }
    }

    @Override
    public void recordVisit(SocialSubjectRef subject, ProfileId visitorProfileId, Instant visitTime) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(visitorProfileId, "visitorProfileId must not be null");
        Objects.requireNonNull(visitTime, "visitTime must not be null");

        String checkSql = """
                SELECT visit_count FROM subject_visits
                WHERE subject_type_id = ? AND subject_key = ? AND visitor_profile_id = ?
                """;
        String updateSql = """
                UPDATE subject_visits
                SET visit_count = visit_count + 1, last_visited_at = ?
                WHERE subject_type_id = ? AND subject_key = ? AND visitor_profile_id = ?
                """;
        String insertSql = """
                INSERT INTO subject_visits (
                    subject_type_id, subject_key, visitor_profile_id, visit_count, first_visited_at, last_visited_at
                ) VALUES (?, ?, ?, 1, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, subject.typeId());
                checkStmt.setString(2, subject.key());
                checkStmt.setString(3, visitorProfileId.value().toString());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setTimestamp(1, Timestamp.from(visitTime));
                    updateStmt.setString(2, subject.typeId());
                    updateStmt.setString(3, subject.key());
                    updateStmt.setString(4, visitorProfileId.value().toString());
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, subject.typeId());
                    insertStmt.setString(2, subject.key());
                    insertStmt.setString(3, visitorProfileId.value().toString());
                    insertStmt.setTimestamp(4, Timestamp.from(visitTime));
                    insertStmt.setTimestamp(5, Timestamp.from(visitTime));
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException(
                    "Failed to record visit for subject: " + subject + ", visitor: " + visitorProfileId, e);
        }
    }

    @Override
    public Optional<SubjectVisit> findVisit(SocialSubjectRef subject, ProfileId visitorProfileId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(visitorProfileId, "visitorProfileId must not be null");

        String sql = """
                SELECT subject_type_id, subject_key, visitor_profile_id, visit_count, first_visited_at, last_visited_at
                FROM subject_visits
                WHERE subject_type_id = ? AND subject_key = ? AND visitor_profile_id = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, subject.typeId());
            stmt.setString(2, subject.key());
            stmt.setString(3, visitorProfileId.value().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapVisit(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException(
                    "Failed to find visit for subject: " + subject + ", visitor: " + visitorProfileId, e);
        }
    }

    @Override
    public void addBookmark(ProfileId profileId, SocialSubjectRef subject) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        String checkSql = """
                SELECT 1 FROM social_bookmarks
                WHERE profile_id = ? AND subject_type_id = ? AND subject_key = ?
                """;
        String insertSql = """
                INSERT INTO social_bookmarks (profile_id, subject_type_id, subject_key, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """;

        try (Connection conn = database.connection()) {
            boolean exists;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, profileId.value().toString());
                checkStmt.setString(2, subject.typeId());
                checkStmt.setString(3, subject.key());
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }

            if (!exists) {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, profileId.value().toString());
                    insertStmt.setString(2, subject.typeId());
                    insertStmt.setString(3, subject.key());
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to add bookmark: " + profileId + " -> " + subject, e);
        }
    }

    @Override
    public void removeBookmark(ProfileId profileId, SocialSubjectRef subject) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        String sql = """
                DELETE FROM social_bookmarks
                WHERE profile_id = ? AND subject_type_id = ? AND subject_key = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());
            stmt.setString(2, subject.typeId());
            stmt.setString(3, subject.key());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to remove bookmark: " + profileId + " -> " + subject, e);
        }
    }

    @Override
    public boolean isBookmarked(ProfileId profileId, SocialSubjectRef subject) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        String sql = """
                SELECT 1 FROM social_bookmarks
                WHERE profile_id = ? AND subject_type_id = ? AND subject_key = ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());
            stmt.setString(2, subject.typeId());
            stmt.setString(3, subject.key());

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to check bookmark: " + profileId + " -> " + subject, e);
        }
    }

    @Override
    public List<SocialSubjectRef> listBookmarks(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = """
                SELECT subject_type_id, subject_key
                FROM social_bookmarks
                WHERE profile_id = ?
                ORDER BY created_at DESC
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());

            List<SocialSubjectRef> list = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new SocialSubjectRef(rs.getString("subject_type_id"), rs.getString("subject_key")));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to list bookmarks for profile: " + profileId, e);
        }
    }

    private static SocialRating mapRating(ResultSet rs) throws SQLException {
        SocialSubjectRef subject = new SocialSubjectRef(rs.getString("subject_type_id"), rs.getString("subject_key"));
        ProfileId rater = ProfileId.of(UUID.fromString(rs.getString("rater_profile_id")));
        int score = rs.getInt("score");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new SocialRating(subject, rater, score, createdAt, updatedAt);
    }

    private static GuestbookEntry mapGuestbook(ResultSet rs) throws SQLException {
        String reviewId = rs.getString("review_id");
        SocialSubjectRef subject = new SocialSubjectRef(rs.getString("subject_type_id"), rs.getString("subject_key"));
        ProfileId author = ProfileId.of(UUID.fromString(rs.getString("author_profile_id")));
        String message = rs.getString("message");
        boolean isHidden = rs.getBoolean("is_hidden");
        boolean isPinned = rs.getBoolean("is_pinned");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        return new GuestbookEntry(reviewId, subject, author, message, isHidden, isPinned, createdAt);
    }

    private static SubjectVisit mapVisit(ResultSet rs) throws SQLException {
        SocialSubjectRef subject = new SocialSubjectRef(rs.getString("subject_type_id"), rs.getString("subject_key"));
        ProfileId visitor = ProfileId.of(UUID.fromString(rs.getString("visitor_profile_id")));
        int visitCount = rs.getInt("visit_count");
        Instant firstVisitedAt = rs.getTimestamp("first_visited_at").toInstant();
        Instant lastVisitedAt = rs.getTimestamp("last_visited_at").toInstant();
        return new SubjectVisit(subject, visitor, visitCount, firstVisitedAt, lastVisitedAt);
    }
}
