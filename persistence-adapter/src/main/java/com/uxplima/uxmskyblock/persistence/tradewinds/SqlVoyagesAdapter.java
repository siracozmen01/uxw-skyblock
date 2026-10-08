package com.uxplima.uxmskyblock.persistence.tradewinds;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyagesPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/** The {@code tradewinds_voyages} table: where each vessel is bound, written under its lease. */
public final class SqlVoyagesAdapter implements VoyagesPort {

    private final Database database;
    private final VesselTransaction transaction;
    private final VesselLeaseGate lease;

    public SqlVoyagesAdapter(Database database) {
        this.database = Objects.requireNonNull(database, "database");
        this.transaction = new VesselTransaction(database);
        this.lease = new VesselLeaseGate(database.dialect());
    }

    @Override
    public Optional<Voyage> voyage(IslandId vessel) {
        Objects.requireNonNull(vessel, "vessel");
        try (Connection conn = database.connection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT port_id, arrives_at FROM tradewinds_voyages WHERE island_id = ?")) {
            ps.setString(1, vessel.value().toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(
                                new Voyage(rs.getString(1), rs.getTimestamp(2).toInstant()))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read where vessel " + vessel + " is bound", e);
        }
    }

    @Override
    public boolean setSail(IslandId vessel, long leaseEpoch, ServerNodeId node, Voyage voyage) {
        Objects.requireNonNull(vessel, "vessel");
        Objects.requireNonNull(voyage, "voyage");
        return transaction.run("set vessel " + vessel + " sailing", conn -> {
            if (!lease.holds(conn, vessel, node, leaseEpoch)) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tradewinds_voyages SET port_id = ?, arrives_at = ? WHERE island_id = ?")) {
                ps.setString(1, voyage.portId());
                ps.setTimestamp(2, Timestamp.from(voyage.arrivesAt()));
                ps.setString(3, vessel.value().toString());
                if (ps.executeUpdate() == 1) {
                    return true;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tradewinds_voyages (island_id, port_id, arrives_at) VALUES (?, ?, ?)")) {
                ps.setString(1, vessel.value().toString());
                ps.setString(2, voyage.portId());
                ps.setTimestamp(3, Timestamp.from(voyage.arrivesAt()));
                return ps.executeUpdate() == 1;
            }
        });
    }
}
