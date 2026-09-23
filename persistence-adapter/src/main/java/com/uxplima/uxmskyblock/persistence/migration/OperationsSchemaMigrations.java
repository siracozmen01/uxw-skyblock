package com.uxplima.uxmskyblock.persistence.migration;

import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Operations schema migrations (V21 - V29).
 * Covers spiral slot pool coordinate recycling, player anti-abuse records,
 * island quarantines, island boosters, island bankruptcies, game mode instances,
 * primary gameplay root references, enterprise activity events, notifications,
 * island homes, island dimensions, profile cosmetics, and island recycle operations.
 */
final class OperationsSchemaMigrations {

    private OperationsSchemaMigrations() {}

    static List<Migration> migrations(Dialect dialect) {
        List<Migration> list = new ArrayList<>(8);
        list.addAll(OperationsSchemaMigrationsV21ToV24.migrations(dialect));
        list.addAll(OperationsSchemaMigrationsV25ToV28.migrations(dialect));
        list.add(OperationsSchemaMigrationsV29.migration(dialect));
        return List.copyOf(list);
    }
}
