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
import com.uxplima.uxmskyblock.core.domain.social.RatingSummary;
import com.uxplima.uxmskyblock.core.domain.social.SocialRating;
import com.uxplima.uxmskyblock.core.domain.social.SocialSubjectRef;
import com.uxplima.uxmskyblock.persistence.sql.UniqueViolations;

/**
 * What players think of a subject, as a score each may give once and change later.
 *
 * <p>The summary is a Bayesian mean: a subject with two five star ratings must not outrank one
 * with four hundred, so a prior pulls a thin sample back toward the middle until enough votes
 * arrive to move it.
 */
final class SqlSocialRatings {

    private final Database database;

    SqlSocialRatings(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    /**
     * Records a player's score for a subject, replacing the one they gave before.
     *
     * <p>It read whether a rating existed and then inserted or updated, with nothing holding the
     * answer between the two. Two first ratings from one player at once, a double click, both read
     * "none" and both inserted, and the second hit the primary key and reached the player as an
     * error. It now updates first, which is the whole of a changed rating, inserts only when there
     * was nothing to update, and turns an insert that lost that race into the update it should have
     * been. The rating keeps the time it was first given.
     */
    public void saveRating(SocialRating rating) {
        Objects.requireNonNull(rating, "rating must not be null");

        String insertSql = """
                INSERT INTO social_ratings (
                    subject_type_id, subject_key, rater_profile_id, score, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = database.connection()) {
            if (update(conn, rating) > 0) {
                return;
            }
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setString(1, rating.subject().typeId());
                insertStmt.setString(2, rating.subject().key());
                insertStmt.setString(3, rating.raterProfileId().value().toString());
                insertStmt.setInt(4, rating.score());
                insertStmt.setTimestamp(5, Timestamp.from(rating.createdAt()));
                insertStmt.setTimestamp(6, Timestamp.from(rating.updatedAt()));
                insertStmt.executeUpdate();
            } catch (SQLException lostTheRace) {
                if (!UniqueViolations.isUniqueViolation(lostTheRace)) {
                    throw lostTheRace;
                }
                update(conn, rating);
            }
        } catch (SQLException e) {
            throw new SocialPersistenceException(
                    "Failed to persist rating for subject: " + rating.subject() + ", rater: " + rating.raterProfileId(),
                    e);
        }
    }

    private static int update(Connection conn, SocialRating rating) throws SQLException {
        String updateSql = """
                UPDATE social_ratings
                SET score = ?, updated_at = ?
                WHERE subject_type_id = ? AND subject_key = ? AND rater_profile_id = ?
                """;
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            updateStmt.setInt(1, rating.score());
            updateStmt.setTimestamp(2, Timestamp.from(rating.updatedAt()));
            updateStmt.setString(3, rating.subject().typeId());
            updateStmt.setString(4, rating.subject().key());
            updateStmt.setString(5, rating.raterProfileId().value().toString());
            return updateStmt.executeUpdate();
        }
    }

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

    private static SocialRating mapRating(ResultSet rs) throws SQLException {
        SocialSubjectRef subject = new SocialSubjectRef(rs.getString("subject_type_id"), rs.getString("subject_key"));
        ProfileId rater = ProfileId.of(UUID.fromString(rs.getString("rater_profile_id")));
        int score = rs.getInt("score");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
        return new SocialRating(subject, rater, score, createdAt, updatedAt);
    }
}
