package com.uxplima.uxmskyblock.persistence.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * The one question every phase of the journal asks: may this session still write?
 *
 * <p>The answer is one row of {@code player_sessions}, read under a row lock, and five conditions
 * on it. Those five were written out three times, once per phase, and one of the three had already
 * drifted: the abort path omits the state check. That drift is correct and the copies hid it.
 *
 * <p>It lives here once, and the one difference is a parameter a caller must name.
 */
final class SessionAuthorityGate {

    private final String selectSql;

    SessionAuthorityGate(String selectSql) {
        this.selectSql = Objects.requireNonNull(selectSql, "selectSql");
    }

    /**
     * Reads the session row and decides.
     *
     * @param requireActiveSession whether a session that is no longer {@code ACTIVE} is a refusal.
     *     A commit needs an active session; an abort is exactly what a dead session needs, so it
     *     passes false and undoes its intent anyway.
     * @return the reason to refuse, or empty when this session still holds authority
     */
    Optional<String> refusal(
            Connection conn,
            PlayerUuid playerUuid,
            ProfileId profileId,
            ServerNodeId nodeId,
            long sessionEpoch,
            boolean requireActiveSession)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, playerUuid.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.of("SESSION_NOT_FOUND");
                }
                String activeProfileId = rs.getString("active_profile_id");
                String authoritativeNode = rs.getString("authoritative_node");
                long epoch = rs.getLong("session_epoch");
                String sessionState = rs.getString("state");
                int leaseValid = rs.getInt("lease_valid");

                if (!profileId.value().toString().equals(activeProfileId)) {
                    return Optional.of("CROSS_PROFILE_MISMATCH");
                }
                if (!nodeId.value().equals(authoritativeNode)) {
                    return Optional.of("WRONG_NODE");
                }
                if (sessionEpoch != epoch) {
                    return Optional.of("STALE_EPOCH");
                }
                if (requireActiveSession && !"ACTIVE".equals(sessionState)) {
                    return Optional.of("SESSION_NOT_ACTIVE");
                }
                if (leaseValid != 1) {
                    return Optional.of("LEASE_EXPIRED");
                }
                return Optional.empty();
            }
        }
    }
}
