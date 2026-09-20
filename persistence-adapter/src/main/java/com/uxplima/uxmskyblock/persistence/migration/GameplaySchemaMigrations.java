package com.uxplima.uxmskyblock.persistence.migration;

import java.util.ArrayList;
import java.util.List;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Gameplay schema migrations (V13 - V20).
 * Covers seasons, snapshots, payouts, social ratings, guestbook reviews,
 * subject visits, bookmarks, alliances, invites, temporary access grants,
 * reward grants/components, warps, bans, vault pages/sessions/escrows/logs,
 * and island missions.
 */
final class GameplaySchemaMigrations {

    private GameplaySchemaMigrations() {}

    static List<Migration> migrations(Dialect dialect) {
        List<Migration> list = new ArrayList<>(8);
        list.addAll(GameplaySchemaMigrationsV13ToV15.migrations(dialect));
        list.addAll(GameplaySchemaMigrationsV16ToV18.migrations(dialect));
        list.addAll(GameplaySchemaMigrationsV19ToV20.migrations(dialect));
        return List.copyOf(list);
    }
}
