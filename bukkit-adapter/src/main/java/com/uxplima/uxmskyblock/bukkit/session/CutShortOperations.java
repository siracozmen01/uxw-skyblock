package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery;
import com.uxplima.uxmskyblock.core.application.inventory.TradeJournalRecovery;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Settles the journaled inventory operations a crash cut short, when a profile's session starts here.
 *
 * <p>Settling writes the journal and never the inventory, so it does not matter whether the player
 * has been given the inventory yet. An operation that cannot be accounted for is quarantined and
 * reported in the server log, which is where staff look for it.
 */
final class CutShortOperations {

    private static final Logger LOGGER = Logger.getLogger(CutShortOperations.class.getName());

    private final InventoryJournalRecovery recovery;
    private final ServerNodeId nodeId;
    private volatile @Nullable TradeJournalRecovery trades;

    CutShortOperations(InventoryJournalRecovery recovery, ServerNodeId nodeId) {
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    /** Settles the trades a crash cut short as well, each side as its player's session starts. */
    void settleTradesWith(TradeJournalRecovery recovery) {
        this.trades = Objects.requireNonNull(recovery, "recovery");
    }

    /** Settles every operation left open on {@code profile}, under the session this node holds at {@code epoch}. */
    void settle(PlayerUuid playerUuid, ProfileId profile, long epoch) {
        settleTrades(playerUuid, profile, epoch);
        for (InventoryJournalRecovery.Settled settled : recovery.recover(playerUuid, profile, nodeId, epoch)) {
            Object[] about = {settled.operationId().value(), playerUuid.value(), settled.settlement()};
            switch (settled.settlement()) {
                case QUARANTINED ->
                    LOGGER.log(
                            Level.SEVERE,
                            "Inventory operation {0} of {1} was cut short and the inventory matches neither side"
                                    + " of it. It is quarantined as RECOVERY_REQUIRED and nothing was refunded.",
                            about);
                case REFUSED ->
                    LOGGER.log(
                            Level.WARNING,
                            "Inventory operation {0} of {1} was cut short and could not be settled now."
                                    + " It is settled the next time the player''s session starts.",
                            about);
                case ABORTED, ROLLED_FORWARD ->
                    LOGGER.log(
                            Level.INFO, "Inventory operation {0} of {1} was cut short and is settled as {2}.", about);
            }
        }
    }

    private void settleTrades(PlayerUuid playerUuid, ProfileId profile, long epoch) {
        TradeJournalRecovery recovery = trades;
        if (recovery == null) {
            return;
        }
        for (TradeJournalRecovery.Settled settled :
                recovery.recover(new TradeJournalPort.Holder(playerUuid, profile, epoch), nodeId)) {
            Object[] about = {settled.operationId().value(), playerUuid.value(), settled.settlement()};
            switch (settled.settlement()) {
                case QUARANTINED ->
                    LOGGER.log(
                            Level.SEVERE,
                            "Trade {0} of {1} was cut short and a side holds neither what it had nor what it was"
                                    + " given. It is quarantined as RECOVERY_REQUIRED and nothing was given or taken.",
                            about);
                case REFUSED ->
                    LOGGER.log(
                            Level.WARNING,
                            "Trade {0} of {1} was cut short and could not be settled now."
                                    + " It is settled the next time the player''s session starts.",
                            about);
                case ABORTED, ROLLED_FORWARD, PUT_BACK, RELEASED ->
                    LOGGER.log(Level.INFO, "Trade {0} of {1} was cut short and is settled as {2}.", about);
            }
        }
    }
}
