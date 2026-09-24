package com.uxplima.uxmskyblock.core.application.vault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.inventory.PlayerStateWrite;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferRecord;
import com.uxplima.uxmskyblock.core.domain.vault.EscrowTransferState;
import com.uxplima.uxmskyblock.core.domain.vault.VaultAuditLogEntry;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import org.jspecify.annotations.Nullable;

/**
 * Persistence port for virtual paged vault storage, exclusive lease sessions, write-ahead escrow transfers,
 * and rolling audit logs.
 */
public interface IslandVaultStoragePort {

    Optional<VaultPage> findPage(IslandId islandId, int page);

    List<VaultPage> findAllPages(IslandId islandId);

    VaultPage createPage(IslandId islandId, int page, byte[] initialContentsNbt, String createdBy);

    /**
     * Atomically acquires an exclusive pessimistic edit lease on the specified vault page.
     * If an unexpired session exists, returns empty.
     */
    Optional<VaultEditSession> acquireEditSession(IslandId islandId, int page, UUID playerUuid, Duration leaseDuration);

    Optional<VaultEditSession> findSession(VaultSessionId sessionId);

    /**
     * Atomically commits a vault page edit within a single atomic transaction:
     * 1. CAS update on island_vault_pages verifying expected page_version and lease_epoch
     * 2. OCC update on profile_inventories if player inventory changes are coordinated
     * 3. Transitions vault_edit_sessions state to COMMITTED and closes lease
     * 4. Updates associated escrow transfers to COMMITTED
     */
    boolean commitEditSession(
            VaultSessionId sessionId, byte[] newContentsNbt, String modifiedBy, @Nullable PlayerStateWrite playerState);

    /**
     * Aborts an active vault edit session, clearing the active lock on the vault page.
     */
    boolean abortEditSession(VaultSessionId sessionId);

    void recordEscrowTransfer(EscrowTransferRecord transfer);

    void updateEscrowTransferState(UUID transferId, EscrowTransferState state);

    List<EscrowTransferRecord> findEscrowTransfers(VaultSessionId sessionId);

    void appendAuditLog(VaultAuditLogEntry logEntry);

    /**
     * Writes a whole commit's audit entries at once.
     *
     * <p>One chest edit moves several slots, and one call for each of them is a round trip for each
     * of them.
     */
    void appendAuditLogs(List<VaultAuditLogEntry> entries);

    List<VaultAuditLogEntry> findRecentAuditLogs(IslandId islandId, int limit);

    /**
     * Keeps the newest {@code keepPerPage} audit entries of every vault page and deletes the rest.
     *
     * <p>The configuration calls this number the entries retained per page. Nothing enforced it, so
     * the table held every deposit and withdrawal a server had ever seen.
     *
     * @return how many entries were deleted
     */
    int trimAuditLogs(int keepPerPage);

    List<VaultEditSession> findExpiredActiveSessions();
}
