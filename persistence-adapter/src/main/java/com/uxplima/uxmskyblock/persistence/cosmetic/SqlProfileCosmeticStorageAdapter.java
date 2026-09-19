package com.uxplima.uxmskyblock.persistence.cosmetic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.uxplima.uxmlib.storage.StorageException;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.cosmetic.ProfileCosmeticStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Production SQL implementation of {@link ProfileCosmeticStoragePort} durably storing
 * cosmetic unlocks across SQLite, MariaDB, and PostgreSQL.
 */
public final class SqlProfileCosmeticStorageAdapter implements ProfileCosmeticStoragePort {

    private final Database database;
    private final Dialect dialect;

    public SqlProfileCosmeticStorageAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database must not be null");
        this.dialect = database.dialect();
    }

    @Override
    public void grantCosmetic(ProfileId profileId, String cosmeticId, @Nullable String grantedBy) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(cosmeticId, "cosmeticId must not be null");

        String sql =
                switch (dialect) {
                    case SQLITE, POSTGRES -> """
                    INSERT INTO profile_cosmetics (profile_id, cosmetic_id, granted_by, unlocked_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (profile_id, cosmetic_id) DO NOTHING
                    """;
                    case MYSQL -> """
                    INSERT IGNORE INTO profile_cosmetics (profile_id, cosmetic_id, granted_by, unlocked_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """;
                    case H2, GENERIC -> throw new UnsupportedOperationException("Unsupported SQL dialect: " + dialect);
                };

        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, cosmeticId);
            ps.setString(3, grantedBy);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to grant cosmetic " + cosmeticId + " for profile " + profileId, e);
        }
    }

    @Override
    public boolean hasCosmetic(ProfileId profileId, String cosmeticId) {
        Objects.requireNonNull(profileId, "profileId must not be null");
        Objects.requireNonNull(cosmeticId, "cosmeticId must not be null");

        String sql = "SELECT 1 FROM profile_cosmetics WHERE profile_id = ? AND cosmetic_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profileId.value().toString());
            ps.setString(2, cosmeticId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to check cosmetic " + cosmeticId + " for profile " + profileId, e);
        }
    }

    @Override
    public Set<String> getCosmetics(ProfileId profileId) {
        Objects.requireNonNull(profileId, "profileId must not be null");

        String sql = "SELECT cosmetic_id FROM profile_cosmetics WHERE profile_id = ?";
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, profileId.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> set = new HashSet<>();
                while (rs.next()) {
                    set.add(rs.getString("cosmetic_id"));
                }
                return Collections.unmodifiableSet(set);
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to load cosmetics for profile " + profileId, e);
        }
    }
}
