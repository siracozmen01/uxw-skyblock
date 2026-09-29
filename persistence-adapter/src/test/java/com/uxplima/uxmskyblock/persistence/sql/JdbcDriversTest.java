package com.uxplima.uxmskyblock.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A driver the plugin ships is found from inside the plugin, where the JVM's own scan never looks.
 */
class JdbcDriversTest {

    @Test
    @DisplayName("Each URL a server may be given names the driver the plugin ships for it")
    void eachUrlNamesItsDriver() {
        assertThat(JdbcDrivers.driverClassFor("jdbc:postgresql://db:5432/skyblock"))
                .contains("org.postgresql.Driver");
        assertThat(JdbcDrivers.driverClassFor("JDBC:MariaDB://db/skyblock")).contains("org.mariadb.jdbc.Driver");
        assertThat(JdbcDrivers.driverClassFor("jdbc:mysql://db/skyblock")).contains("org.mariadb.jdbc.Driver");
        assertThat(JdbcDrivers.driverClassFor("jdbc:oracle:thin:@db")).isEmpty();
    }

    /**
     * The plugin as the server loads it: its classes and its drivers in a class loader of their own. The
     * JVM registered the test's copy of each driver, which this loader does not see as its own, so the
     * driver is found here only if the plugin loads it.
     */
    @Test
    @DisplayName("From a class loader of its own, the PostgreSQL and MariaDB drivers are found")
    void thePluginFindsItsOwnDrivers() throws Exception {
        for (String url :
                new String[] {"jdbc:postgresql://127.0.0.1:1/skyblock", "jdbc:mariadb://127.0.0.1:1/skyblock"}) {
            try (URLClassLoader plugin = pluginLoader()) {
                Driver found = register(plugin, url);

                assertThat(found.getClass().getClassLoader())
                        .describedAs("the driver the plugin itself carries, for %s", url)
                        .isSameAs(plugin);
            }
        }
    }

    @Test
    @DisplayName("A URL no driver accepts is refused with the schemes the plugin ships")
    void anUnknownUrlIsRefused() throws Exception {
        try (URLClassLoader plugin = pluginLoader()) {
            assertThatThrownBy(() -> register(plugin, "jdbc:nothing://db"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("jdbc:postgresql:");
        }
    }

    private static Driver register(ClassLoader plugin, String url) throws Exception {
        Method register = plugin.loadClass(JdbcDrivers.class.getName()).getMethod("register", String.class);
        try {
            return (Driver) register.invoke(null, url);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static URLClassLoader pluginLoader() throws ClassNotFoundException {
        return new URLClassLoader(
                new URL[] {
                    where(JdbcDrivers.class),
                    where(Class.forName("org.postgresql.Driver")),
                    where(Class.forName("org.mariadb.jdbc.Driver"))
                },
                ClassLoader.getPlatformClassLoader());
    }

    private static URL where(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
