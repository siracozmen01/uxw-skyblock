package com.uxplima.uxmskyblock.persistence.sql;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Makes the JDBC driver a connection URL needs known before the pool asks for it.
 *
 * <p>The drivers ship inside the plugin, and a JDBC driver is found through the services file the
 * JVM reads once, with the server's class loader, before any plugin exists. The plugin's drivers are
 * never in that scan: a driver is only known once its class is loaded, and nothing loaded ours. A
 * server pointed at PostgreSQL did not start, "No suitable driver", though the driver sat in the jar.
 * The driver is loaded here with the plugin's own class loader, which registers it, and a URL no
 * driver answers is refused with its reason before the pool is built.
 */
public final class JdbcDrivers {

    private JdbcDrivers() {}

    /** The driver class the plugin ships for a URL, if it ships one. */
    public static Optional<String> driverClassFor(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:")) {
            return Optional.of("org.postgresql.Driver");
        }
        if (lower.startsWith("jdbc:mariadb:") || lower.startsWith("jdbc:mysql:")) {
            return Optional.of("org.mariadb.jdbc.Driver");
        }
        return Optional.empty();
    }

    /**
     * Loads the driver the URL needs and answers it.
     *
     * @throws IllegalStateException when no driver on this server accepts the URL
     */
    public static Driver register(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Optional<String> shipped = driverClassFor(jdbcUrl);
        if (shipped.isPresent()) {
            try {
                // Loading the class runs its registration with DriverManager.
                Class.forName(shipped.get(), true, JdbcDrivers.class.getClassLoader());
            } catch (ClassNotFoundException missing) {
                // Not in this build. The server may still carry one that accepts the URL.
            }
        }
        try {
            return DriverManager.getDriver(jdbcUrl);
        } catch (SQLException none) {
            throw new IllegalStateException(
                    "No JDBC driver on this server accepts the database URL. The plugin ships drivers for "
                            + "jdbc:mariadb:, jdbc:mysql: and jdbc:postgresql: URLs.",
                    none);
        }
    }
}
