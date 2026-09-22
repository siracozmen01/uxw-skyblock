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
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.social.GuestbookEntry;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;

/**
 * What players wrote on a subject, and which of those the owner keeps at the top or hides.
 *
 * <p>A hidden entry is not deleted. The author can still see it was written, and an owner who
 * hides a review cannot make it never have happened.
 */
final class SqlSocialGuestbook {

    private final Database database;

    SqlSocialGuestbook(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

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

    /**
     * Pins one entry while its guestbook is under the limit, in one statement.
     *
     * <p>The count is inside the statement, wrapped in a derived table because MariaDB refuses a
     * bare subquery over the table an UPDATE is working on. Counting first and writing second let
     * two owners pinning at once both read the same count and both pin.
     */
    public boolean pinGuestbookEntryWithin(SocialSubjectRef subject, String reviewId, int maxPinned) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");
        if (maxPinned < 1) {
            throw new IllegalArgumentException("maxPinned must be >= 1: " + maxPinned);
        }

        String sql = """
                UPDATE guestbook_reviews
                SET is_pinned = ?
                WHERE review_id = ?
                  AND subject_type_id = ?
                  AND subject_key = ?
                  AND is_pinned = ?
                  AND (
                      SELECT already_pinned FROM (
                          SELECT COUNT(*) AS already_pinned
                          FROM guestbook_reviews
                          WHERE subject_type_id = ? AND subject_key = ? AND is_pinned = ?
                      ) counted
                  ) < ?
                """;

        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, true);
            stmt.setString(2, reviewId);
            stmt.setString(3, subject.typeId());
            stmt.setString(4, subject.key());
            stmt.setBoolean(5, false);
            stmt.setString(6, subject.typeId());
            stmt.setString(7, subject.key());
            stmt.setBoolean(8, true);
            stmt.setInt(9, maxPinned);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to pin guestbook review: " + reviewId, e);
        }
    }

    /** Unpins one of a subject's entries. The subject is part of the statement, not a hope. */
    public boolean unpinGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = """
                UPDATE guestbook_reviews
                SET is_pinned = ?
                WHERE review_id = ? AND subject_type_id = ? AND subject_key = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, false);
            stmt.setString(2, reviewId);
            stmt.setString(3, subject.typeId());
            stmt.setString(4, subject.key());
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to unpin guestbook review: " + reviewId, e);
        }
    }

    public boolean setGuestbookHidden(SocialSubjectRef subject, String reviewId, boolean hidden) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = """
                UPDATE guestbook_reviews
                SET is_hidden = ?
                WHERE review_id = ? AND subject_type_id = ? AND subject_key = ?
                """;
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, hidden);
            stmt.setString(2, reviewId);
            stmt.setString(3, subject.typeId());
            stmt.setString(4, subject.key());
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to update hidden state for review: " + reviewId, e);
        }
    }

    public boolean deleteGuestbookEntry(SocialSubjectRef subject, String reviewId) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(reviewId, "reviewId must not be null");

        String sql = "DELETE FROM guestbook_reviews WHERE review_id = ? AND subject_type_id = ? AND subject_key = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, reviewId);
            stmt.setString(2, subject.typeId());
            stmt.setString(3, subject.key());
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new SocialPersistenceException("Failed to delete guestbook entry: " + reviewId, e);
        }
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
}
