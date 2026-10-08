package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Dialect;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Whether a node may write a vessel's rows now: while it holds the vessel's lease at the epoch it names
 * and the lease runs. The lease row is locked for the rest of the transaction.
 */
final class VesselLeaseGate {

    private final String select;

    VesselLeaseGate(Dialect dialect) {
        this.select = "SELECT authoritative_node, authority_epoch, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM mode_owned_authorities WHERE provider_id = ? AND root_key = ?"
                + (dialect == Dialect.SQLITE ? "" : " FOR UPDATE");
    }

    boolean holds(Connection conn, IslandId vessel, ServerNodeId node, long epoch) throws SQLException {
        Objects.requireNonNull(vessel, "vessel");
        Objects.requireNonNull(node, "node");
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, CargoJournalPort.LEASE_PROVIDER);
            ps.setString(2, vessel.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        && node.value().equals(rs.getString("authoritative_node"))
                        && rs.getLong("authority_epoch") == epoch
                        && rs.getInt("lease_valid") == 1;
            }
        }
    }
}
