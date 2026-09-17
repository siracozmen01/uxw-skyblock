package com.uxplima.uxmskyblock.core.domain.backup;

import java.util.Locale;

/**
 * Supported SQL dialects for dialect-correct disaster backup operations.
 */
public enum DatabaseBackupDialect {
    SQLITE,
    MARIADB,
    POSTGRESQL;

    public static DatabaseBackupDialect fromIdentifier(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Dialect identifier cannot be null");
        }
        return switch (identifier.trim().toUpperCase(Locale.ROOT)) {
            case "SQLITE" -> SQLITE;
            case "MARIADB", "MYSQL" -> MARIADB;
            case "POSTGRES", "POSTGRESQL" -> POSTGRESQL;
            default -> throw new IllegalArgumentException("Unsupported database backup dialect: " + identifier);
        };
    }
}
