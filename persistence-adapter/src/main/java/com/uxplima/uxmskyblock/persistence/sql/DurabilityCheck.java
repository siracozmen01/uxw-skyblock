package com.uxplima.uxmskyblock.persistence.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

import com.uxplima.uxmlib.storage.sql.Database;
import org.jspecify.annotations.Nullable;

/**
 * Whether the database keeps a commit through a power loss.
 *
 * <p>A bank balance, a vault page and a reward claim are only as durable as the commit that wrote
 * them. An engine told to acknowledge a commit before it reaches the disk loses the last ones when
 * the machine loses power, and a player who saw an item leave the vault and arrive in their
 * inventory can find the vault holding it again. The persistence specification names the settings
 * a production server needs; this reads them at startup.
 *
 * <p>SQLite needs {@code synchronous} at FULL or above. MariaDB and MySQL need
 * {@code innodb_flush_log_at_trx_commit = 1}, and {@code sync_binlog = 1} when the binary log is on.
 * PostgreSQL needs {@code fsync} on and {@code synchronous_commit} not off.
 */
public final class DurabilityCheck {

    private static final Logger LOGGER = Logger.getLogger(DurabilityCheck.class.getName());

    /** What startup does about a setting that would lose a commit. */
    public enum Profile {
        /** Warns once, loudly, and starts. */
        DEVELOPMENT,
        /** Refuses to start. */
        PRODUCTION_STRICT;

        /** The profile an operator named, or {@link #DEVELOPMENT} when they named none or a wrong one. */
        public static Profile parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return DEVELOPMENT;
            }
            String wanted = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
            for (Profile profile : values()) {
                if (profile.name().equals(wanted)) {
                    return profile;
                }
            }
            LOGGER.warning(() -> "database.durability-profile \"" + raw + "\" is not a profile. Using DEVELOPMENT.");
            return DEVELOPMENT;
        }
    }

    private DurabilityCheck() {
        throw new UnsupportedOperationException("DurabilityCheck is a check, not a thing to hold.");
    }

    /** Every setting on this database that would lose a commit, in words an operator can act on. */
    public static List<String> findProblems(Database database) {
        Objects.requireNonNull(database, "database must not be null");
        List<String> problems = new ArrayList<>();
        try (Connection conn = database.connection()) {
            switch (database.dialect()) {
                case SQLITE -> {
                    long synchronous = number(conn, "PRAGMA synchronous");
                    if (synchronous < 2) {
                        problems.add("SQLite synchronous is " + synchronousName(synchronous)
                                + "; a commit survives a power loss only at FULL or EXTRA.");
                    }
                }
                case MYSQL -> {
                    long flush = number(conn, "SELECT @@innodb_flush_log_at_trx_commit");
                    if (flush != 1) {
                        problems.add("innodb_flush_log_at_trx_commit is " + flush + "; it has to be 1.");
                    }
                    if (number(conn, "SELECT @@log_bin") == 1) {
                        long syncBinlog = number(conn, "SELECT @@sync_binlog");
                        if (syncBinlog != 1) {
                            problems.add("sync_binlog is " + syncBinlog + " with the binary log on; it has to be 1.");
                        }
                    }
                }
                case POSTGRES -> {
                    if (!"on".equalsIgnoreCase(text(conn, "SHOW fsync"))) {
                        problems.add("fsync is off; it has to be on.");
                    }
                    if ("off".equalsIgnoreCase(text(conn, "SHOW synchronous_commit"))) {
                        problems.add("synchronous_commit is off; it has to be on.");
                    }
                }
                default -> {}
            }
        } catch (SQLException e) {
            problems.add("The durability settings could not be read: " + e.getMessage());
        }
        return problems;
    }

    /**
     * Reads the settings and acts on them as {@code profile} says.
     *
     * @throws FatalDurabilityConfigurationException under {@link Profile#PRODUCTION_STRICT} when a
     *     setting would lose a commit
     */
    public static void enforce(Database database, Profile profile) {
        List<String> problems = findProblems(database);
        if (problems.isEmpty()) {
            return;
        }
        String report = "NON_DURABLE_STORAGE_CONFIG_DETECTED: " + String.join(" ", problems);
        if (profile == Profile.PRODUCTION_STRICT) {
            throw new FatalDurabilityConfigurationException(
                    report + " database.durability-profile is PRODUCTION_STRICT, so the plugin will not start.");
        }
        LOGGER.warning(() -> report + " Commits may be lost if the machine loses power."
                + " Set database.durability-profile to PRODUCTION_STRICT to refuse to start instead.");
    }

    private static long number(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static String text(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? String.valueOf(rs.getString(1)) : "";
        }
    }

    private static String synchronousName(long level) {
        return switch ((int) level) {
            case 0 -> "OFF";
            case 1 -> "NORMAL";
            default -> Long.toString(level);
        };
    }
}
