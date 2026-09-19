package com.uxplima.uxmskyblock.persistence.snapshot;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;

/**
 * SQL persistence adapter for capturing and restoring root-scoped relational data snapshots (Section 2.29).
 *
 * <p>Pure SQL relational persistence without any Bukkit or Paper world dependencies.
 */
public final class SqlRootRelationalSnapshotAdapter implements RootRelationalSnapshotPort {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<TableSpec> TABLES = List.of(
            new TableSpec("islands", "id"),
            new TableSpec("island_authorities", "island_id"),
            new TableSpec("island_locations", "island_id"),
            new TableSpec("island_members", "island_id"),
            new TableSpec("island_roles", "island_id"),
            new TableSpec("island_role_permissions", "island_id"),
            new TableSpec("island_flags", "island_id"),
            new TableSpec("island_banks", "island_id"),
            new TableSpec("island_upgrades", "island_id"),
            new TableSpec("island_warps", "island_id"),
            new TableSpec("island_boosters", "island_id"),
            new TableSpec("island_bankruptcies", "island_id"),
            new TableSpec("island_homes", "island_id")
    );

    private record TableSpec(String tableName, String rootColumn) {}

    private final DataSource dataSource;

    public SqlRootRelationalSnapshotAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public byte[] captureRelationalSnapshot(PrimaryGameplayRootRef rootRef, long revision) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");

        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("instanceId", rootRef.gameModeInstanceId().value().toString());
        rootJson.addProperty("rootId", rootRef.rootId());
        rootJson.addProperty("rootType", rootRef.rootType());
        rootJson.addProperty("revision", revision);
        rootJson.addProperty("capturedAt", Instant.now().toString());

        JsonObject tablesJson = new JsonObject();

        try (Connection conn = dataSource.getConnection()) {
            for (TableSpec spec : TABLES) {
                JsonArray rowsArray = readTableRows(conn, spec.tableName(), spec.rootColumn(), rootRef.rootId());
                tablesJson.add(spec.tableName(), rowsArray);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to capture relational snapshot for root " + rootRef.rootId(), e);
        }

        rootJson.add("tables", tablesJson);
        return GSON.toJson(rootJson).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void restoreRelationalSnapshot(PrimaryGameplayRootRef rootRef, byte[] snapshotPayload) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");
        Objects.requireNonNull(snapshotPayload, "snapshotPayload must not be null");

        String jsonStr = new String(snapshotPayload, StandardCharsets.UTF_8);
        JsonObject rootJson = JsonParser.parseString(jsonStr).getAsJsonObject();
        String payloadRootId = rootJson.get("rootId").getAsString();
        if (!payloadRootId.equalsIgnoreCase(rootRef.rootId())) {
            throw new IllegalArgumentException(
                    "Snapshot rootId mismatch: expected " + rootRef.rootId() + " but payload is for " + payloadRootId);
        }

        JsonObject tablesJson = rootJson.getAsJsonObject("tables");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // Disable foreign keys temporarily for clean deletion and restore
                try {
                    stmt.execute("PRAGMA foreign_keys = OFF;");
                } catch (SQLException ignored) {
                    // Not SQLite, ignore
                }

                // Delete child tables first (reverse order)
                for (int i = TABLES.size() - 1; i >= 0; i--) {
                    TableSpec spec = TABLES.get(i);
                    String deleteSql = "DELETE FROM " + spec.tableName() + " WHERE " + spec.rootColumn() + " = ?";
                    try (PreparedStatement delStmt = conn.prepareStatement(deleteSql)) {
                        delStmt.setString(1, rootRef.rootId());
                        delStmt.executeUpdate();
                    }
                }

                // Insert saved rows in forward order
                for (TableSpec spec : TABLES) {
                    if (tablesJson.has(spec.tableName())) {
                        JsonArray rowsArray = tablesJson.getAsJsonArray(spec.tableName());
                        insertTableRows(conn, spec.tableName(), rowsArray);
                    }
                }

                try {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                } catch (SQLException ignored) {
                    // Not SQLite, ignore
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Failed to restore relational snapshot for root " + rootRef.rootId(), e);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error during relational snapshot restoration", e);
        }
    }

    private static JsonArray readTableRows(Connection conn, String tableName, String rootColumn, String rootId)
            throws SQLException {
        JsonArray array = new JsonArray();
        String sql = "SELECT * FROM " + tableName + " WHERE " + rootColumn + " = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, rootId);
            try (ResultSet rs = stmt.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    for (int i = 1; i <= colCount; i++) {
                        String colName = meta.getColumnName(i);
                        Object val = rs.getObject(i);
                        if (val == null) {
                            row.add(colName, null);
                        } else if (val instanceof Number n) {
                            row.addProperty(colName, n);
                        } else if (val instanceof Boolean b) {
                            row.addProperty(colName, b);
                        } else {
                            row.addProperty(colName, val.toString());
                        }
                    }
                    array.add(row);
                }
            }
        }
        return array;
    }

    private static void insertTableRows(Connection conn, String tableName, JsonArray rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }

        for (JsonElement elem : rows) {
            JsonObject row = elem.getAsJsonObject();
            List<String> cols = new ArrayList<>();
            List<Object> values = new ArrayList<>();

            for (Map.Entry<String, JsonElement> entry : row.entrySet()) {
                cols.add(entry.getKey());
                JsonElement jsonVal = entry.getValue();
                if (jsonVal == null || jsonVal.isJsonNull()) {
                    values.add(null);
                } else if (jsonVal.getAsJsonPrimitive().isNumber()) {
                    values.add(jsonVal.getAsNumber());
                } else if (jsonVal.getAsJsonPrimitive().isBoolean()) {
                    values.add(jsonVal.getAsBoolean());
                } else {
                    values.add(jsonVal.getAsString());
                }
            }

            StringBuilder sql = new StringBuilder("INSERT INTO ").append(tableName).append(" (");
            StringBuilder placeholders = new StringBuilder(" VALUES (");
            for (int i = 0; i < cols.size(); i++) {
                sql.append(cols.get(i));
                placeholders.append("?");
                if (i < cols.size() - 1) {
                    sql.append(", ");
                    placeholders.append(", ");
                }
            }
            sql.append(")").append(placeholders).append(")");

            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < values.size(); i++) {
                    stmt.setObject(i + 1, values.get(i));
                }
                stmt.executeUpdate();
            }
        }
    }
}
