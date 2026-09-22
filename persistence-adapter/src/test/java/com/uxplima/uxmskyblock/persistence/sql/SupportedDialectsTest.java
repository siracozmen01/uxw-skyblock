package com.uxplima.uxmskyblock.persistence.sql;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uxplima.uxmlib.storage.sql.Dialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A database this plugin cannot serve is refused before it is half used.
 *
 * <p>Nine classes each wrote this check out before it moved here, and the message drifted between
 * them. Refusing at construction rather than at the first query is the point: a schema that half
 * applies is worse than a server that will not start.
 */
class SupportedDialectsTest {

    @Test
    @DisplayName("The three this plugin runs on are accepted")
    void thethreeAreAccepted() {
        for (Dialect dialect : new Dialect[] {Dialect.SQLITE, Dialect.MYSQL, Dialect.POSTGRES}) {
            assertThatCode(() -> SupportedDialects.require(dialect, "islands"))
                    .describedAs("%s is one of the three", dialect)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("Anything else is refused, and the refusal names the part that refused")
    void anythingElseIsRefused() {
        for (Dialect dialect : new Dialect[] {Dialect.H2, Dialect.GENERIC}) {
            assertThatThrownBy(() -> SupportedDialects.require(dialect, "the island bank"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(dialect.name())
                    .hasMessageContaining("the island bank")
                    .describedAs("an operator reads which subsystem would not start")
                    .hasMessageContaining("SQLite");
        }
    }

    @Test
    @DisplayName("Every dialect the library knows is either accepted or refused, never ignored")
    void everyDialectIsAnswered() {
        for (Dialect dialect : Dialect.values()) {
            try {
                SupportedDialects.require(dialect, "islands");
            } catch (IllegalArgumentException refused) {
                continue;
            }
            assertThatCode(() -> SupportedDialects.require(dialect, "islands"))
                    .describedAs("%s was accepted, so it has to be one of the three", dialect)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("It is a check, not a thing to hold")
    void itIsNotAThingToHold() throws Exception {
        java.lang.reflect.Constructor<SupportedDialects> constructor = SupportedDialects.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasRootCauseInstanceOf(UnsupportedOperationException.class);
    }
}
