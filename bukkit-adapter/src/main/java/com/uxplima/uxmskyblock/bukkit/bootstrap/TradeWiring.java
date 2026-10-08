package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.Objects;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.creative.SealedInventory;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.trade.TradeListener;
import com.uxplima.uxmskyblock.bukkit.trade.Trades;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;
import org.jspecify.annotations.Nullable;

/** Trading between players, while the operator lets them trade. */
public final class TradeWiring {

    private final TradeConfiguration config;
    private final Trades trades;
    private final TradeListener listener;
    private final com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener holdStill;

    public TradeWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            SchedulerPort scheduler) {
        this.config = Objects.requireNonNull(configuration.tradeConfig(), "tradeConfig must not be null");
        PlayerSessionCoordinator coordinator = authority.sessionCoordinator();
        this.trades = new Trades(
                config,
                configuration.messages(),
                scheduler,
                new Trades.Sessions() {
                    @Override
                    public @Nullable ActiveSession session(UUID player) {
                        ActiveSession session = coordinator.getActiveSession(player);
                        return session == null || session.isFenced() || !coordinator.inPlay(player) ? null : session;
                    }

                    @Override
                    public void fence(UUID player, String why) {
                        coordinator.selfFencePlayer(PlayerUuid.of(player), why);
                    }
                },
                new TradeExchange(persistence.tradeJournalPort(), authority.serverNodeId()),
                SealedInventory::holds,
                System::nanoTime);
        this.listener = new TradeListener(trades);
        this.holdStill = new com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener(trades::exchanging);
    }

    public boolean enabled() {
        return config.enabled();
    }

    public Trades trades() {
        return trades;
    }

    public TradeListener listener() {
        return listener;
    }

    /** Holds an inventory still while its trade is carried out. */
    public com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener holdStill() {
        return holdStill;
    }
}
