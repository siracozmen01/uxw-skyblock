package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHoldListener;
import com.uxplima.uxmskyblock.bukkit.tradewinds.CargoHolds;
import com.uxplima.uxmskyblock.bukkit.tradewinds.VesselStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoTransfer;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.persistence.bootstrap.PersistenceBootstrap;

/** The TradeWinds game mode, while the operator lets islands be trading vessels. */
public final class TradeWindsWiring {

    private static final Logger LOGGER = Logger.getLogger(TradeWindsWiring.class.getName());

    private final TradeWindsConfiguration config;
    private final VesselService service;
    private final SchedulerPort scheduler;
    private final CargoHolds holds;
    private final CargoHoldListener listener;
    private final com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener holdStill;

    public TradeWindsWiring(
            ConfigurationWiring configuration,
            PersistenceBootstrap persistence,
            AuthorityWiring authority,
            SchedulerPort scheduler,
            com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener islands) {
        this.config = Objects.requireNonNull(configuration.tradeWindsConfig(), "tradeWindsConfig must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.service = new VesselService(persistence.vesselsPort());
        com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator coordinator = authority.sessionCoordinator();
        this.holds = new CargoHolds(
                service,
                persistence.vesselsPort(),
                new VesselLease(persistence.rootAuthorityPort(), authority.serverNodeId(), java.time.Clock.systemUTC()),
                new CargoTransfer(persistence.cargoJournalPort(), authority.serverNodeId()),
                islands::findIslandAt,
                new CargoHolds.Sessions() {
                    @Override
                    public com.uxplima.uxmskyblock.bukkit.session.@org.jspecify.annotations.Nullable ActiveSession
                            session(java.util.UUID player) {
                        com.uxplima.uxmskyblock.bukkit.session.ActiveSession session =
                                coordinator.getActiveSession(player);
                        return session == null || session.isFenced() || !coordinator.inPlay(player) ? null : session;
                    }

                    @Override
                    public void fence(java.util.UUID player, String why) {
                        coordinator.selfFencePlayer(
                                com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid.of(player), why);
                    }
                },
                scheduler,
                configuration.messages(),
                com.uxplima.uxmskyblock.bukkit.creative.SealedInventory::holds,
                config.holdRows());
        this.listener = new CargoHoldListener(holds);
        this.holdStill = new com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener(holds::moving);
        scheduler.async(() -> {
            try {
                int count = service.prime();
                LOGGER.fine(() -> count + " TradeWinds vessels are in memory.");
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "The TradeWinds vessels could not be read ahead.", e);
            }
        });
    }

    /** The cargo holds of vessels. */
    public CargoHolds holds() {
        return holds;
    }

    /** The events of cargo hold windows. */
    public CargoHoldListener listener() {
        return listener;
    }

    /** Holds an inventory still while a move into or out of a hold is carried out. */
    public com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener holdStill() {
        return holdStill;
    }

    public VesselService service() {
        return service;
    }

    public TradeWindsConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.enabled();
    }

    /** The action that launches an island as a vessel, while TradeWinds is enabled, and none otherwise. */
    public List<CreationActionProvider<IslandStart>> startActions() {
        return config.enabled() ? List.of(new VesselStart(service, scheduler, config.sea())) : List.of();
    }
}
