package com.uxplima.uxmskyblock.core.domain.session;

/**
 * Legal canonical player session states in SQL persistence storage.
 */
public enum SessionState {
    ACTIVE,
    DRAINING,
    HANDOFF_READY,
    RECOVERING
}
