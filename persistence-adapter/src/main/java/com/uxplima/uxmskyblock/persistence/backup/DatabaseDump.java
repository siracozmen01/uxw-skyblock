package com.uxplima.uxmskyblock.persistence.backup;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Every row of every table as data, not as SQL text, and back.
 *
 * <p>The backup wrote each value into an INSERT statement with {@code toString()}. A binary column
 * came out as the text of a Java array reference, so a restore replaced every inventory, ender chest
 * and vault page with it; a boolean went in as a number PostgreSQL refuses, MariaDB read a backslash
 * as an escape, and a value ending a line in a semicolon split the statement in two. Each value is now
 * written with its type and, where it is bytes or text, in base64, and put back through a bound
 * parameter of that type.
 *
 * <p>Tables are written parents first, following their foreign keys, deleted children first and
 * filled parents first on the way back, which is the order PostgreSQL insists on. SQLite values are
 * written by what they hold rather than by the declared type, because SQLite keeps a timestamp as text
 * and writing it back as a timestamp would store a number in its place.
 */
final class DatabaseDump {

    private static final String TABLE = "TABLE";
    private static final String ROW = "ROW";
    private static final String NULL = "~";

    private DatabaseDump() {}

    /** Appends every table of the database {@code conn} reads, parents first. */
    static void write(Connection conn, Dialect dialect, StringBuilder out) throws SQLException {
        for (String table : parentsFirst(conn)) {
            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT * FROM " + table)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columns = meta.getColumnCount();
                out.append(TABLE).append('\t').append(table);
                for (int i = 1; i <= columns; i++) {
                    out.append('\t').append(meta.getColumnName(i)).append(':').append(meta.getColumnType(i));
                }
                out.append('\n');
                while (rs.next()) {
                    out.append(ROW);
                    for (int i = 1; i <= columns; i++) {
                        out.append('\t').append(encode(rs, i, meta.getColumnType(i), dialect));
                    }
                    out.append('\n');
                }
            }
        }
    }

    /** Replaces every row of the database {@code conn} writes with the rows of {@code body}. */
    static void restore(Connection conn, Dialect dialect, String body) throws SQLException {
        List<String> live = parentsFirst(conn);
        try (Statement stmt = conn.createStatement()) {
            if (dialect == Dialect.POSTGRES) {
                // PostgreSQL keeps its foreign keys on. Every table is emptied in one statement, which
                // it allows whatever points at what, and a key made deferrable waits for the commit.
                stmt.execute("SET CONSTRAINTS ALL DEFERRED");
                stmt.executeUpdate("TRUNCATE TABLE " + String.join(", ", live));
            } else {
                for (int i = live.size() - 1; i >= 0; i--) {
                    stmt.executeUpdate("DELETE FROM " + live.get(i));
                }
            }
        }
        PreparedStatement insert = null;
        int[] types = new int[0];
        try {
            for (String line : body.lines().toList()) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells[0].equals(TABLE)) {
                    if (insert != null) {
                        insert.close();
                    }
                    List<String> names = new ArrayList<>();
                    types = new int[cells.length - 2];
                    for (int i = 2; i < cells.length; i++) {
                        int colon = cells[i].lastIndexOf(':');
                        names.add(cells[i].substring(0, colon));
                        types[i - 2] = Integer.parseInt(cells[i].substring(colon + 1));
                    }
                    insert = conn.prepareStatement("INSERT INTO " + cells[1] + " (" + String.join(", ", names)
                            + ") VALUES (" + String.join(", ", java.util.Collections.nCopies(names.size(), "?"))
                            + ")");
                } else if (cells[0].equals(ROW) && insert != null) {
                    for (int i = 1; i < cells.length; i++) {
                        bind(insert, i, cells[i], types[i - 1]);
                    }
                    insert.executeUpdate();
                } else {
                    throw new IllegalArgumentException("The backup has a line it does not know: " + cells[0]);
                }
            }
        } finally {
            if (insert != null) {
                insert.close();
            }
        }
    }

    private static String encode(ResultSet rs, int i, int type, Dialect dialect) throws SQLException {
        if (dialect == Dialect.SQLITE) {
            Object value = rs.getObject(i);
            if (value == null) {
                return NULL;
            }
            if (value instanceof byte[] bytes) {
                return "B" + base64(bytes);
            }
            if (value instanceof Integer || value instanceof Long) {
                return "L" + ((Number) value).longValue();
            }
            if (value instanceof Number number) {
                return "D" + number.doubleValue();
            }
            return "S" + base64(value.toString().getBytes(StandardCharsets.UTF_8));
        }
        String encoded =
                switch (type) {
                    case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                        byte[] bytes = rs.getBytes(i);
                        yield bytes == null ? NULL : "B" + base64(bytes);
                    }
                    case Types.BIT, Types.BOOLEAN -> "Z" + (rs.getBoolean(i) ? 1 : 0);
                    case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT -> "L" + rs.getLong(i);
                    case Types.REAL, Types.FLOAT, Types.DOUBLE -> "D" + rs.getDouble(i);
                    case Types.NUMERIC, Types.DECIMAL -> {
                        BigDecimal number = rs.getBigDecimal(i);
                        yield number == null ? NULL : "N" + number.toPlainString();
                    }
                    case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> {
                        Timestamp at = rs.getTimestamp(i);
                        yield at == null ? NULL : "T" + at.getTime() + ":" + at.getNanos();
                    }
                    default -> {
                        String text = rs.getString(i);
                        yield text == null ? NULL : "S" + base64(text.getBytes(StandardCharsets.UTF_8));
                    }
                };
        return rs.wasNull() ? NULL : encoded;
    }

    private static void bind(PreparedStatement ps, int i, String cell, int type) throws SQLException {
        if (cell.equals(NULL)) {
            ps.setNull(i, type);
            return;
        }
        String value = cell.substring(1);
        switch (cell.charAt(0)) {
            case 'B' -> ps.setBytes(i, Base64.getDecoder().decode(value));
            case 'Z' -> ps.setBoolean(i, value.equals("1"));
            case 'L' -> ps.setLong(i, Long.parseLong(value));
            case 'D' -> ps.setDouble(i, Double.parseDouble(value));
            case 'N' -> ps.setBigDecimal(i, new BigDecimal(value));
            case 'T' -> {
                int colon = value.indexOf(':');
                Timestamp at = new Timestamp(Long.parseLong(value.substring(0, colon)));
                at.setNanos(Integer.parseInt(value.substring(colon + 1)));
                ps.setTimestamp(i, at);
            }
            case 'S' -> ps.setString(i, new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("The backup has a value it does not know: " + cell);
        }
    }

    /** The database's own tables, each after every table its foreign keys point at. */
    static List<String> parentsFirst(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        String catalog = conn.getCatalog();
        String schema = conn.getSchema();
        Map<String, String> byLower = new LinkedHashMap<>();
        try (ResultSet tables = meta.getTables(catalog, schema, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                if (!name.toLowerCase(Locale.ROOT).startsWith("sqlite_")) {
                    byLower.put(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        Map<String, Set<String>> parents = new HashMap<>();
        for (String table : byLower.keySet()) {
            Set<String> of = new LinkedHashSet<>();
            try (ResultSet keys = meta.getImportedKeys(catalog, schema, byLower.get(table))) {
                while (keys.next()) {
                    if (keys.getShort("DEFERRABILITY") != DatabaseMetaData.importedKeyNotDeferrable) {
                        // A key checked at commit does not decide the order rows are written in.
                        continue;
                    }
                    String parent = keys.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
                    if (!parent.equals(table) && byLower.containsKey(parent)) {
                        of.add(parent);
                    }
                }
            }
            parents.put(table, of);
        }
        List<String> ordered = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(byLower.keySet());
        int stalled = 0;
        while (!pending.isEmpty() && stalled <= pending.size()) {
            String table = pending.removeFirst();
            if (placed.containsAll(parents.get(table))) {
                ordered.add(byLower.get(table));
                placed.add(table);
                stalled = 0;
            } else {
                pending.addLast(table);
                stalled++;
            }
        }
        // A cycle has no parents-first order; what is left keeps the order the database gave.
        for (String table : pending) {
            ordered.add(byLower.get(table));
        }
        return ordered;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
