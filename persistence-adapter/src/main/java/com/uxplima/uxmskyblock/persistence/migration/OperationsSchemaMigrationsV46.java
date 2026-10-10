package com.uxplima.uxmskyblock.persistence.migration;

import static com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations.V46_DESCRIPTION;

import com.uxplima.uxmlib.storage.migration.Migration;
import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * V46: the rows a deleted island left behind on SQLite.
 *
 * <p>Every table under an island declares {@code ON DELETE CASCADE}, and SQLite left foreign keys off
 * on every connection, so deleting an island on SQLite removed the island row and nothing under it. Its
 * owner stayed a member of an island that no longer existed, could not make another one, and the next
 * island given the freed slot could not be written over the old location.
 *
 * <p>The connection enforces the keys now. This removes what the earlier deletes left, from the island
 * down, parents before children. It removes nothing on MySQL, MariaDB or PostgreSQL, which always
 * cascaded. The player tables are left alone: they point at each other, and none of them was orphaned
 * by a deleted island.
 */
final class OperationsSchemaMigrationsV46 {

    private OperationsSchemaMigrationsV46() {}

    /** The same statements on every engine: each deletes the rows whose parent row is gone. */
    static Migration migration(Dialect dialect) {
        return new Migration(46, V46_DESCRIPTION, ORPHANS);
    }

    private static final String ORPHANS = """
                DELETE FROM acid_island_state WHERE acid_island_state.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = acid_island_state.island_id);
                DELETE FROM bank_transactions WHERE bank_transactions.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = bank_transactions.island_id);
                DELETE FROM boxed_instance_state WHERE boxed_instance_state.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = boxed_instance_state.island_id);
                DELETE FROM brix_plots WHERE brix_plots.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = brix_plots.island_id);
                DELETE FROM chunkblock_territory_claims WHERE chunkblock_territory_claims.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = chunkblock_territory_claims.island_id);
                DELETE FROM island_authorities WHERE island_authorities.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_authorities.island_id);
                DELETE FROM island_bankruptcies WHERE island_bankruptcies.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_bankruptcies.island_id);
                DELETE FROM island_banks WHERE island_banks.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_banks.island_id);
                DELETE FROM island_bans WHERE island_bans.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_bans.island_id);
                DELETE FROM island_boosters WHERE island_boosters.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_boosters.island_id);
                DELETE FROM island_flags WHERE island_flags.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_flags.island_id);
                DELETE FROM island_homes WHERE island_homes.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_homes.island_id);
                DELETE FROM island_locations WHERE island_locations.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_locations.island_id);
                DELETE FROM island_members WHERE island_members.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_members.island_id);
                DELETE FROM island_missions WHERE island_missions.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_missions.island_id);
                DELETE FROM island_quarantines WHERE island_quarantines.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_quarantines.island_id);
                DELETE FROM island_roles WHERE island_roles.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_roles.island_id);
                DELETE FROM island_upgrades WHERE island_upgrades.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_upgrades.island_id);
                DELETE FROM island_vault_pages WHERE island_vault_pages.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_vault_pages.island_id);
                DELETE FROM island_warps WHERE island_warps.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = island_warps.island_id);
                DELETE FROM oneblock_progress WHERE oneblock_progress.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = oneblock_progress.island_id);
                DELETE FROM parkour_courses WHERE parkour_courses.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = parkour_courses.island_id);
                DELETE FROM parkour_records WHERE parkour_records.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = parkour_records.island_id);
                DELETE FROM poseidon_instance_state WHERE poseidon_instance_state.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = poseidon_instance_state.island_id);
                DELETE FROM stranger_claims WHERE stranger_claims.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = stranger_claims.island_id);
                DELETE FROM tradewinds_vessels WHERE tradewinds_vessels.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = tradewinds_vessels.island_id);
                DELETE FROM vault_audit_logs WHERE vault_audit_logs.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = vault_audit_logs.island_id);
                DELETE FROM vault_edit_sessions WHERE vault_edit_sessions.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM islands p WHERE p.id = vault_edit_sessions.island_id);
                DELETE FROM boxed_advancements WHERE boxed_advancements.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM boxed_instance_state p WHERE p.island_id = boxed_advancements.island_id);
                DELETE FROM island_role_permissions WHERE island_role_permissions.island_id IS NOT NULL AND island_role_permissions.role_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM island_roles p WHERE p.island_id = island_role_permissions.island_id AND p.role_id = island_role_permissions.role_id);
                DELETE FROM tradewinds_orders WHERE tradewinds_orders.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tradewinds_vessels p WHERE p.island_id = tradewinds_orders.island_id);
                DELETE FROM tradewinds_standing WHERE tradewinds_standing.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tradewinds_vessels p WHERE p.island_id = tradewinds_standing.island_id);
                DELETE FROM tradewinds_voyages WHERE tradewinds_voyages.island_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM tradewinds_vessels p WHERE p.island_id = tradewinds_voyages.island_id);
                DELETE FROM vault_escrow_transfers WHERE vault_escrow_transfers.session_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM vault_edit_sessions p WHERE p.session_id = vault_escrow_transfers.session_id);
                """;
}
