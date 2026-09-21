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
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.core.domain.social.SubjectVisit;

/**
 * Who has been to a subject and who saved it to come back to.
 *
 * <p>A visit is counted, not listed: the row carries a count and the first and last time, so a
 * popular island does not grow a row per arrival.
 */
final class SqlSocialVisits {

    private final Database database;

    SqlSocialVisits(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

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

    private static SubjectVisit mapVisit(ResultSet rs) throws SQLException {
        SocialSubjectRef subject = new SocialSubjectRef(rs.getString("subject_type_id"), rs.getString("subject_key"));
        ProfileId visitor = ProfileId.of(UUID.fromString(rs.getString("visitor_profile_id")));
        int visitCount = rs.getInt("visit_count");
        Instant firstVisitedAt = rs.getTimestamp("first_visited_at").toInstant();
        Instant lastVisitedAt = rs.getTimestamp("last_visited_at").toInstant();
        return new SubjectVisit(subject, visitor, visitCount, firstVisitedAt, lastVisitedAt);
    }
}
