package com.uxplima.uxmskyblock.core.domain.inventory;

/**
 * Source-frozen lifecycle states for {@code inventory_mutation_journals}.
 *
 * <p>Canonical states per {@code docs/PERSISTENCE_SPECIFICATION.md} and {@code docs/GAMEMODE_ARCHITECTURE.md}:
 * <ul>
 *   <li>{@link #INTENT}: Write-ahead intent persisted; no live mutation may execute before this state.</li>
 *   <li>{@link #APPLYING}: Live in-memory slot mutation is being applied across execution contexts.</li>
 *   <li>{@link #APPLIED}: In-memory slot mutations applied and confirmed by participants.</li>
 *   <li>{@link #COMMITTED}: Canonical durable aggregate updated with OCC; terminal successful state.</li>
 *   <li>{@link #ABORTED}: Intent safely aborted prior to live commit; terminal aborted state.</li>
 *   <li>{@link #RECOVERY_REQUIRED}: Irreconcilable conflict or ambiguous crash state requiring quarantine/reconciliation.</li>
 * </ul>
 */
public enum InventoryMutationJournalState {
    INTENT,
    APPLYING,
    APPLIED,
    COMMITTED,
    ABORTED,
    RECOVERY_REQUIRED
}
