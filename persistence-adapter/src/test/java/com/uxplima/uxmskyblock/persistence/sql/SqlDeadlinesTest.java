package com.uxplima.uxmskyblock.persistence.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The lock wait and the idle transaction limit reach every server connection through its URL. */
class SqlDeadlinesTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    @Test
    @DisplayName("MariaDB and MySQL get the InnoDB lock wait as a session variable, in whole seconds")
    void mariaDbGetsTheLockWait() {
        assertThat(SqlDeadlines.apply("jdbc:mariadb://db:3306/sky", ONE_SECOND, FIVE_SECONDS))
                .isEqualTo("jdbc:mariadb://db:3306/sky?sessionVariables=innodb_lock_wait_timeout=1");
        assertThat(SqlDeadlines.apply("jdbc:mysql://db/sky?useSSL=false", Duration.ofMillis(1500), FIVE_SECONDS))
                .isEqualTo("jdbc:mysql://db/sky?useSSL=false&sessionVariables=innodb_lock_wait_timeout=2");
        assertThat(SqlDeadlines.apply(
                        "jdbc:mariadb://db/sky?sessionVariables=wait_timeout=60", ONE_SECOND, FIVE_SECONDS))
                .isEqualTo("jdbc:mariadb://db/sky?sessionVariables=innodb_lock_wait_timeout=1,wait_timeout=60");
    }

    @Test
    @DisplayName("PostgreSQL gets the lock wait and the idle transaction limit as server options")
    void postgresGetsBoth() {
        assertThat(SqlDeadlines.apply("jdbc:postgresql://db:5432/sky", ONE_SECOND, FIVE_SECONDS))
                .isEqualTo("jdbc:postgresql://db:5432/sky?options=-c%20lock_timeout%3D1000%20-c%20"
                        + "idle_in_transaction_session_timeout%3D5000");
    }

    @Test
    @DisplayName("A URL that already names the setting keeps the operator's value, and SQLite is left alone")
    void theOperatorsValueStands() {
        String named = "jdbc:mariadb://db/sky?sessionVariables=innodb_lock_wait_timeout=7";
        assertThat(SqlDeadlines.apply(named, ONE_SECOND, FIVE_SECONDS)).isEqualTo(named);
        String options = "jdbc:postgresql://db/sky?options=-c%20search_path%3Dsky";
        assertThat(SqlDeadlines.apply(options, ONE_SECOND, FIVE_SECONDS)).isEqualTo(options);
        assertThat(SqlDeadlines.apply("jdbc:sqlite:sky.db", ONE_SECOND, FIVE_SECONDS))
                .isEqualTo("jdbc:sqlite:sky.db");
    }

    @Test
    @DisplayName("A wait that is not positive is refused")
    void aWaitMustBePositive() {
        assertThatThrownBy(() -> SqlDeadlines.apply("jdbc:mariadb://db/sky", Duration.ZERO, FIVE_SECONDS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
