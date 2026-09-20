package com.uxplima.uxmskyblock.persistence.migration;

import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Core schema migrations (V1 - V12).
 * Covers player accounts, profiles, sessions, inventories, mutation journals,
 * profile switch operations, islands, authorities, banks, upgrades, backups,
 * outbox/inbox events, world grid allocations, and economy sagas.
 */
final class CoreSchemaMigrations {

    private CoreSchemaMigrations() {}

    static List<Migration> migrations(Dialect dialect) {
        List<Migration> list = new ArrayList<>(12);
        list.addAll(CoreSchemaMigrationsV1ToV4.migrations(dialect));
        list.addAll(CoreSchemaMigrationsV5ToV6.migrations(dialect));
        list.addAll(CoreSchemaMigrationsV7ToV9.migrations(dialect));
        list.addAll(CoreSchemaMigrationsV10ToV12.migrations(dialect));
        return List.copyOf(list);
    }
}
