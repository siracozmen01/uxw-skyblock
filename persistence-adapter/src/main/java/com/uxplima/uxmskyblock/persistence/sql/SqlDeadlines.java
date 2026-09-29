package com.uxplima.uxmskyblock.persistence.sql;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * How long a statement on a server database waits for a row lock, and how long a transaction nobody is
 * driving any more is kept.
 *
 * <p>A node that dies holding a row lock keeps it until the database notices the connection is gone.
 * Everything that wanted the row waited behind it: fifty seconds on MariaDB and MySQL, and for ever on
 * PostgreSQL, whose lock timeout is off by default. A heartbeat or a takeover stuck there holds a pool
 * connection and a thread, and a few of them hold the whole pool. The wait is now bounded: a statement
 * that cannot have its lock in time fails, its transaction rolls back, and the caller fails closed and
 * tries again later. It never goes round the lock.
 *
 * <p>PostgreSQL also ends a transaction left open and idle, so a client that stopped mid transaction
 * gives its locks back without waiting for the socket to be found dead. MariaDB and MySQL have no such
 * setting for a transaction alone; a process that dies closes its socket, and the server rolls back.
 *
 * <p>The settings go on the connection URL, so every pooled connection carries them from the first
 * statement. A URL that already names one keeps the operator's value.
 */
public final class SqlDeadlines {

    /** The lock wait the specification sets, when the operator names none. */
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(1);

    /** How long PostgreSQL keeps a transaction open with nothing happening in it, when the operator names none. */
    public static final Duration DEFAULT_IDLE_IN_TRANSACTION = Duration.ofSeconds(5);

    private SqlDeadlines() {}

    /** {@code jdbcUrl} with the lock wait and, on PostgreSQL, the idle transaction limit added. */
    public static String apply(String jdbcUrl, Duration lockTimeout, Duration idleInTransaction) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        requirePositive(lockTimeout, "lockTimeout");
        requirePositive(idleInTransaction, "idleInTransaction");
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:mariadb:") || lower.startsWith("jdbc:mysql:")) {
            if (lower.contains("innodb_lock_wait_timeout")) {
                return jdbcUrl;
            }
            // InnoDB counts whole seconds, and a wait shorter than one is not one it can keep.
            long seconds = Math.max(1, (lockTimeout.toMillis() + 999) / 1000);
            if (lower.contains("sessionvariables=")) {
                int at = lower.indexOf("sessionvariables=") + "sessionvariables=".length();
                return jdbcUrl.substring(0, at) + "innodb_lock_wait_timeout=" + seconds + "," + jdbcUrl.substring(at);
            }
            return withParameter(jdbcUrl, "sessionVariables=innodb_lock_wait_timeout=" + seconds);
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            if (lower.contains("options=")) {
                // The operator set server options of their own, and those are theirs to finish.
                return jdbcUrl;
            }
            String options = "-c lock_timeout=" + lockTimeout.toMillis() + " -c idle_in_transaction_session_timeout="
                    + idleInTransaction.toMillis();
            return withParameter(
                    jdbcUrl,
                    "options="
                            + URLEncoder.encode(options, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return jdbcUrl;
    }

    private static String withParameter(String jdbcUrl, String parameter) {
        return jdbcUrl + (jdbcUrl.indexOf('?') >= 0 ? "&" : "?") + parameter;
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive: " + duration);
        }
    }
}
