package com.uxplima.uxmskyblock.persistence.sql;

import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * The three databases this plugin runs on, and the refusal for anything else.
 *
 * <p>SQLite for a single server, MariaDB or PostgreSQL for a network of them. Every other dialect
 * the library knows is refused at construction rather than at the first query, because a schema
 * that half applies is worse than a server that will not start.
 *
 * <p>Nine classes each wrote this check out, and the message drifted between them.
 */
public final class SupportedDialects {

    private SupportedDialects() {
        throw new UnsupportedOperationException("SupportedDialects is a check, not a thing to hold.");
    }

    /**
     * Refuses a dialect this plugin cannot serve.
     *
     * @param subsystem what is being built, so the operator reads which part refused
     */
    public static void require(Dialect dialect, String subsystem) {
        Objects.requireNonNull(dialect, "dialect must not be null");
        Objects.requireNonNull(subsystem, "subsystem must not be null");
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException("Unsupported SQL dialect: " + dialect + ". Skyblock " + subsystem
                        + " supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
    }
}
