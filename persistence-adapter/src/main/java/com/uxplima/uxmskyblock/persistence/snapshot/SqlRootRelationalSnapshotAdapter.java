package com.uxplima.uxmskyblock.persistence.snapshot;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.uxmskyblock.core.application.snapshot.RootRelationalSnapshotPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;

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
            new TableSpec("island_homes", "island_id"));

    /**
     * Tables a restore never writes back, whatever the payload holds and whatever mode was asked
     * for. Money a player has already spent cannot be un-spent by restoring last night's file, and
     * a bankruptcy that was settled cannot be reinstated. The authority lease is live state: writing
     * back an old epoch would hand the island to a node that has already lost it.
     */
    private static final Set<String> NEVER_RESTORED =
            Set.of("island_banks", "island_bankruptcies", "island_boosters", "island_authorities");

    /**
     * The row every other table hangs off, by a key that cascades. It is never deleted: deleting it
     * took the bank, its history, the vault pages and every other child with it on an engine that
     * enforces the key. It is written only when it is missing, and a full island restore gives back
     * its name. Its lifecycle, level, worth and version only move forward.
     */
    private static final String ISLANDS = "islands";

    /**
     * Tables only a full island restore writes: who belongs to the island, what they may do and what
     * it has bought.
     */
    private static final Set<String> MEMBERSHIP_TABLES =
            Set.of("island_members", "island_roles", "island_role_permissions", "island_upgrades");

    /** The tables {@code mode} is allowed to write, in the order the foreign keys want them. */
    private static List<TableSpec> tablesFor(RestoreMode mode) {
        List<TableSpec> allowed = new ArrayList<>();
        for (TableSpec spec : TABLES) {
            if (NEVER_RESTORED.contains(spec.tableName())) {
                continue;
            }
            if (!mode.restoresMembership() && MEMBERSHIP_TABLES.contains(spec.tableName())) {
                continue;
            }
            allowed.add(spec);
        }
        return List.copyOf(allowed);
    }

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
    public void restoreRelationalSnapshot(PrimaryGameplayRootRef rootRef, byte[] snapshotPayload, RestoreMode mode) {
        Objects.requireNonNull(rootRef, "rootRef must not be null");
        Objects.requireNonNull(snapshotPayload, "snapshotPayload must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        if (!mode.restoresRelationalState()) {
            return;
        }
        List<TableSpec> restorable = tablesFor(mode);

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
            try {
                // Children first, so no key is ever left pointing at nothing. The island row itself
                // stays where it is.
                for (int i = restorable.size() - 1; i >= 0; i--) {
                    TableSpec spec = restorable.get(i);
                    if (spec.tableName().equals(ISLANDS)) {
                        continue;
                    }
                    String deleteSql = "DELETE FROM " + spec.tableName() + " WHERE " + spec.rootColumn() + " = ?";
                    try (PreparedStatement delStmt = conn.prepareStatement(deleteSql)) {
                        delStmt.setString(1, rootRef.rootId());
                        delStmt.executeUpdate();
                    }
                }

                for (TableSpec spec : restorable) {
                    if (!tablesJson.has(spec.tableName())) {
                        continue;
                    }
                    JsonArray rowsArray = tablesJson.getAsJsonArray(spec.tableName());
                    if (spec.tableName().equals(ISLANDS)) {
                        restoreIslandRow(conn, rootRef.rootId(), rowsArray, mode);
                    } else {
                        insertTableRows(conn, spec.tableName(), rowsArray);
                    }
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

    /** Writes the island row back when it is gone, and otherwise only what the mode may give back. */
    private static void restoreIslandRow(Connection conn, String islandId, JsonArray rows, RestoreMode mode)
            throws SQLException {
        boolean present;
        try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM islands WHERE id = ?")) {
            stmt.setString(1, islandId);
            try (ResultSet rs = stmt.executeQuery()) {
                present = rs.next();
            }
        }
        if (!present) {
            insertTableRows(conn, ISLANDS, rows);
            return;
        }
        if (mode.restoresMembership() && !rows.isEmpty()) {
            JsonElement name = rows.get(0).getAsJsonObject().get("custom_name");
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE islands SET custom_name = ? WHERE id = ?")) {
                stmt.setString(1, name == null || name.isJsonNull() ? null : name.getAsString());
                stmt.setString(2, islandId);
                stmt.executeUpdate();
            }
        }
        // Past both the live version and the one the backup holds, by a margin no ordinary write
        // closes: every cache and every writer holding an older version is refused and reads again,
        // rather than laying the state it had before the restore over the restored one.
        long snapshotVersion = versionOf(rows);
        try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE islands SET version = (CASE WHEN version > ? THEN version ELSE ? END) + ? WHERE id = ?")) {
            stmt.setLong(1, snapshotVersion);
            stmt.setLong(2, snapshotVersion);
            stmt.setLong(3, RESTORE_VERSION_STEP);
            stmt.setString(4, islandId);
            stmt.executeUpdate();
        }
    }

    /** How far a restore moves the island's version past the newer of the live and backed up ones. */
    static final long RESTORE_VERSION_STEP = 100L;

    private static long versionOf(JsonArray rows) {
        if (rows.isEmpty()) {
            return 0L;
        }
        JsonElement version = rows.get(0).getAsJsonObject().get("version");
        if (version == null || version.isJsonNull()) {
            return 0L;
        }
        try {
            return version.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException notANumber) {
            return 0L;
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

    /**
     * The columns of {@code tableName} that hold a time, which the snapshot keeps as text. PostgreSQL
     * refuses text for a timestamp column, so those go back as timestamps. SQLite keeps a time as text
     * in the first place, so there it goes back the way it was read.
     */
    private static Set<String> timeColumnsOf(Connection conn, String tableName) throws SQLException {
        if (conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("sqlite")) {
            return Set.of();
        }
        Set<String> found = new HashSet<>();
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM " + tableName + " WHERE 1 = 0");
                ResultSet rs = stmt.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                int type = meta.getColumnType(i);
                if (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE) {
                    found.add(meta.getColumnName(i));
                }
            }
        }
        return found;
    }

    private static void insertTableRows(Connection conn, String tableName, JsonArray rows) throws SQLException {
        if (rows.isEmpty()) {
            return;
        }
        Set<String> timeColumns = timeColumnsOf(conn, tableName);

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
                } else if (timeColumns.contains(entry.getKey())) {
                    values.add(Timestamp.valueOf(jsonVal.getAsString()));
                } else {
                    values.add(jsonVal.getAsString());
                }
            }

            StringBuilder sql =
                    new StringBuilder("INSERT INTO ").append(tableName).append(" (");
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
