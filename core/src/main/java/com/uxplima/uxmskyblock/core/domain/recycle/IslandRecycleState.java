package com.uxplima.uxmskyblock.core.domain.recycle;

/**
 * Lifecycle states of an enterprise island recycle / deletion transactional operation.
 */
public enum IslandRecycleState {
    REQUESTED,
    BACKUP_COMPLETE,
    VOIDING,
    VOID_COMPLETE,
    CANONICAL_DELETE,
    SLOT_RELEASED,
    COMPLETED,
    FAILED
}
