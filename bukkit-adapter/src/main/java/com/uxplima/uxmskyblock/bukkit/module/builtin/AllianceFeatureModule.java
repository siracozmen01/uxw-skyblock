package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.core.application.alliance.IslandAllianceService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in internal feature module managing bilateral island alliances,
 * diplomatic relations, friendly-fire shielding, and alliance chat routing.
 *
 * <p>It also deletes the invitations nobody answered. An expired invite cannot be accepted, so it
 * was never wrong to leave one in place, but nothing ever deleted one either: the table held every
 * invitation the server had ever seen.
 */
public final class AllianceFeatureModule extends AbstractFeatureModule {

    private static final Logger LOGGER = Logger.getLogger(AllianceFeatureModule.class.getName());

    private final IslandAllianceService allianceService;
    private final @Nullable SchedulerPort scheduler;
    private final Duration sweepInterval;

    private @Nullable AutoCloseable sweepTask;

    public AllianceFeatureModule(IslandAllianceService allianceService) {
        this(allianceService, null, AllianceFeatureModule.DEFAULT_SWEEP_INTERVAL);
    }

    /** How often the expired invites are deleted, when the caller names no interval. */
    public static final Duration DEFAULT_SWEEP_INTERVAL = Duration.ofMinutes(5);

    /** The canonical constructor, carrying the scheduler the sweep runs on. */
    public AllianceFeatureModule(
            IslandAllianceService allianceService, @Nullable SchedulerPort scheduler, Duration sweepInterval) {
        super(new ModuleDescriptor(
                "alliances",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-alliances"),
                ">=1.0.0",
                false));
        this.allianceService = Objects.requireNonNull(allianceService, "allianceService must not be null");
        this.scheduler = scheduler;
        this.sweepInterval = Objects.requireNonNull(sweepInterval, "sweepInterval must not be null");
        if (sweepInterval.isNegative() || sweepInterval.isZero()) {
            throw new IllegalArgumentException("sweepInterval must be positive: " + sweepInterval);
        }
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandAllianceService.class, allianceService);
        SchedulerPort port = this.scheduler;
        if (port != null) {
            this.sweepTask = port.repeatAsync(allianceService::purgeExpiredInvites, sweepInterval, sweepInterval);
        }
    }

    @Override
    protected void onDisable() {
        AutoCloseable task = this.sweepTask;
        this.sweepTask = null;
        if (task == null) {
            return;
        }
        try {
            task.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e, () -> "Cancelling the alliance invite sweep failed.");
        }
    }
}
