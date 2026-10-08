package com.uxplima.uxmskyblock.persistence.inventory;

import java.util.Objects;

import com.uxplima.uxmlib.storage.sql.Dialect;

/**
 * Every statement the inventory mutation journal runs, written once for the dialect in use.
 *
 * <p>The adapter held thirteen statement fields and four builders alongside the two phase protocol
 * they serve. The protocol is what is worth reading; the SQL is what is worth checking against a
 * schema. They are separated here so a reader can do one without wading through the other.
 *
 * <p>A row lock is a {@code FOR UPDATE} on a server database and nothing at all on SQLite, which
 * locks the whole file for the transaction instead. That difference lives here and nowhere else.
 */
final class InventoryMutationJournalSql {

    private final String selectSessionAuthority;
    private final String selectInventoryVersion;
    private final String selectJournalForUpdate;
    private final String selectParticipantForUpdate;
    private final String selectJournalRead;
    private final String selectParticipantRead;
    private final String insertJournal;
    private final String insertParticipant;
    private final String updateInventoryOcc;
    private final String updateSessionLastDurableVersion;
    private final String updateJournalState;
    private final String updateParticipantState;
    private final String deleteAbortedParticipants;
    private final String deleteAbortedJournal;

    private InventoryMutationJournalSql(Dialect dialect) {
        this.selectSessionAuthority = selectSessionAuthority(dialect);
        this.selectInventoryVersion = selectInventoryVersion(dialect);
        this.selectJournalForUpdate = selectJournal(dialect, true);
        this.selectParticipantForUpdate = selectParticipant(dialect, true);
        this.selectJournalRead = selectJournal(dialect, false);
        this.selectParticipantRead = selectParticipant(dialect, false);

        // Postgres types a json column strictly and refuses a bare string bind.
        String payloadBind = dialect == Dialect.POSTGRES ? "CAST(? AS json)" : "?";
        this.insertJournal = "INSERT INTO inventory_mutation_journals "
                + "(operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at) "
                + "VALUES (?, ?, 'INTENT', 1, " + payloadBind + ", ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        this.insertParticipant = "INSERT INTO inventory_mutation_participants "
                + "(operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                + "expected_version, authority_type, authority_id, authority_epoch, "
                + "before_fingerprint, after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at) "
                + "VALUES (?, 0, 'PLAYER_INVENTORY', 'PROFILE', ?, ?, 'SERVER_NODE', ?, ?, ?, ?, 'PENDING', "
                + payloadBind + ", CURRENT_TIMESTAMP)";

        this.updateInventoryOcc = "UPDATE profile_inventories "
                + "SET profile_inventory_version = profile_inventory_version + 1, "
                + "inventory_nbt = ?, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE profile_id = ? "
                + "AND profile_inventory_version = ?";

        this.updateSessionLastDurableVersion = "UPDATE player_sessions "
                + "SET last_durable_inventory_version = ?, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE player_uuid = ?";

        this.updateJournalState = "UPDATE inventory_mutation_journals "
                + "SET state = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ?";

        this.updateParticipantState = "UPDATE inventory_mutation_participants "
                + "SET durable_apply_state = ?, updated_at = CURRENT_TIMESTAMP "
                + "WHERE operation_id = ? AND participant_index = ?";

        this.deleteAbortedParticipants = "DELETE FROM inventory_mutation_participants WHERE operation_id = ? "
                + "AND EXISTS (SELECT 1 FROM inventory_mutation_journals j "
                + "WHERE j.operation_id = ? AND j.state = 'ABORTED')";
        this.deleteAbortedJournal =
                "DELETE FROM inventory_mutation_journals WHERE operation_id = ? AND state = 'ABORTED'";
    }

    /** Builds the statement set for {@code dialect}, refusing a dialect this journal cannot serve. */
    static InventoryMutationJournalSql forDialect(Dialect dialect) {
        Objects.requireNonNull(dialect, "dialect");
        switch (dialect) {
            case SQLITE, MYSQL, POSTGRES -> {}
            case H2, GENERIC ->
                throw new IllegalArgumentException(
                        "Unsupported SQL dialect: " + dialect
                                + ". Skyblock inventory persistence supports SQLite, MariaDB (upstream MYSQL), and PostgreSQL.");
        }
        return new InventoryMutationJournalSql(dialect);
    }

    private static String selectSessionAuthority(Dialect dialect) {
        String base = "SELECT active_profile_id, authoritative_node, session_epoch, state, "
                + "(CASE WHEN lease_expires_at >= CURRENT_TIMESTAMP THEN 1 ELSE 0 END) AS lease_valid "
                + "FROM player_sessions "
                + "WHERE player_uuid = ?";
        return dialect == Dialect.SQLITE ? base : base + " FOR UPDATE";
    }

    private static String selectInventoryVersion(Dialect dialect) {
        String base = "SELECT profile_inventory_version FROM profile_inventories WHERE profile_id = ?";
        return dialect == Dialect.SQLITE ? base : base + " FOR UPDATE";
    }

    private static String selectJournal(Dialect dialect, boolean forUpdate) {
        String base =
                "SELECT operation_id, operation_type, state, participant_count, payload, expires_at, created_at, updated_at "
                        + "FROM inventory_mutation_journals WHERE operation_id = ?";
        return (forUpdate && dialect != Dialect.SQLITE) ? base + " FOR UPDATE" : base;
    }

    private static String selectParticipant(Dialect dialect, boolean forUpdate) {
        String base = "SELECT operation_id, participant_index, inventory_type, owner_root_type, owner_root_id, "
                + "expected_version, authority_type, authority_id, authority_epoch, before_fingerprint, "
                + "after_fingerprint, durable_apply_state, mutation_delta_payload, updated_at "
                + "FROM inventory_mutation_participants WHERE operation_id = ? AND participant_index = ?";
        return (forUpdate && dialect != Dialect.SQLITE) ? base + " FOR UPDATE" : base;
    }

    String selectSessionAuthority() {
        return selectSessionAuthority;
    }

    String selectInventoryVersion() {
        return selectInventoryVersion;
    }

    String selectJournalForUpdate() {
        return selectJournalForUpdate;
    }

    String selectParticipantForUpdate() {
        return selectParticipantForUpdate;
    }

    String selectJournalRead() {
        return selectJournalRead;
    }

    String selectParticipantRead() {
        return selectParticipantRead;
    }

    String insertJournal() {
        return insertJournal;
    }

    String insertParticipant() {
        return insertParticipant;
    }

    String updateInventoryOcc() {
        return updateInventoryOcc;
    }

    String updateSessionLastDurableVersion() {
        return updateSessionLastDurableVersion;
    }

    String updateJournalState() {
        return updateJournalState;
    }

    String updateParticipantState() {
        return updateParticipantState;
    }

    String deleteAbortedParticipants() {
        return deleteAbortedParticipants;
    }

    String deleteAbortedJournal() {
        return deleteAbortedJournal;
    }
}
