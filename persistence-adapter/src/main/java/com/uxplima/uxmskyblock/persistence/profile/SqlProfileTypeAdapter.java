package com.uxplima.uxmskyblock.persistence.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.profile.ProfileTypePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;

/**
 * Reads the ruleset a profile plays under out of {@code player_profiles}.
 *
 * <p>The column has been written since the first migration and nothing ever read it back.
 */
public final class SqlProfileTypeAdapter implements ProfileTypePort {

    private static final Logger LOGGER = Logger.getLogger(SqlProfileTypeAdapter.class.getName());

    private final Database database;

    public SqlProfileTypeAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
    }

    @Override
    public ProfileType typeOf(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = "SELECT profile_type FROM player_profiles WHERE profile_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, profileId.value().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return ProfileType.CLASSIC;
                }
                return parse(rs.getString("profile_type"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the ruleset of profile " + profileId, e);
        }
    }

    /**
     * A stored word this build does not know is read as classic.
     *
     * <p>Refusing to let a player move because a newer build wrote a ruleset name into their row is
     * worse than treating them as the ordinary case, and the log says which word it was.
     */
    private static ProfileType parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return ProfileType.CLASSIC;
        }
        String normalised = stored.strip().toUpperCase(Locale.ROOT);
        for (ProfileType type : ProfileType.values()) {
            if (type.name().equals(normalised)) {
                return type;
            }
        }
        LOGGER.warning(() -> "A profile names the ruleset \"" + stored + "\", which this build does not know. "
                + "It is read as CLASSIC.");
        return ProfileType.CLASSIC;
    }
}
