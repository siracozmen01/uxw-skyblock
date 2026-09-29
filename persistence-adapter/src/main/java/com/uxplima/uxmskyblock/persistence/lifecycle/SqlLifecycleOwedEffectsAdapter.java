package com.uxplima.uxmskyblock.persistence.lifecycle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleOwedEffectsPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;

/**
 * {@link LifecycleOwedEffectsPort} over the {@code lifecycle_owed_effects} table, on every supported
 * database.
 *
 * <p>An effect is owed by deleting its row and writing it again in one transaction, which every engine
 * reads the same way, where an upsert is written four ways.
 */
public final class SqlLifecycleOwedEffectsAdapter implements LifecycleOwedEffectsPort {

    private final DataSource dataSource;

    public SqlLifecycleOwedEffectsAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public void owe(PlayerUuid playerUuid, Set<LifecycleEffect> effects) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(effects, "effects must not be null");
        if (effects.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement(
                            "DELETE FROM lifecycle_owed_effects WHERE player_uuid = ? AND effect = ?");
                    PreparedStatement insert = conn.prepareStatement(
                            "INSERT INTO lifecycle_owed_effects (player_uuid, effect) VALUES (?, ?)")) {
                for (LifecycleEffect effect : effects) {
                    delete.setString(1, playerUuid.value().toString());
                    delete.setString(2, effect.name());
                    delete.executeUpdate();
                    insert.setString(1, playerUuid.value().toString());
                    insert.setString(2, effect.name());
                    insert.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not record what " + playerUuid + " is owed", e);
        }
    }

    @Override
    public Set<LifecycleEffect> owed(PlayerUuid playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps =
                        conn.prepareStatement("SELECT effect FROM lifecycle_owed_effects WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.value().toString());
            EnumSet<LifecycleEffect> owed = EnumSet.noneOf(LifecycleEffect.class);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // A name a later version wrote and this one does not know is left for that version.
                    effectNamed(rs.getString(1)).ifPresent(owed::add);
                }
            }
            return Set.copyOf(owed);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read what " + playerUuid + " is owed", e);
        }
    }

    @Override
    public void settle(PlayerUuid playerUuid, Set<LifecycleEffect> paid) {
        Objects.requireNonNull(playerUuid, "playerUuid must not be null");
        Objects.requireNonNull(paid, "paid must not be null");
        if (paid.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM lifecycle_owed_effects WHERE player_uuid = ? AND effect = ?")) {
            for (LifecycleEffect effect : paid) {
                ps.setString(1, playerUuid.value().toString());
                ps.setString(2, effect.name());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not settle what " + playerUuid + " was owed", e);
        }
    }

    private static Optional<LifecycleEffect> effectNamed(String name) {
        for (LifecycleEffect effect : LifecycleEffect.values()) {
            if (effect.name().equals(name)) {
                return Optional.of(effect);
            }
        }
        return Optional.empty();
    }
}
