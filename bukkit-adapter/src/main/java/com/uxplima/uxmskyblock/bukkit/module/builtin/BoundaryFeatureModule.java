package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.boundary.IslandBoundaryListener;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module managing island boundaries, spillover shielding, and particle perimeter projections.
 */
public final class BoundaryFeatureModule extends AbstractFeatureModule {

    private final IslandBoundaryService boundaryService;
    private final IslandBoundaryListener boundaryListener;
    private final SchedulerPort schedulerPort;
    private @Nullable AutoCloseable particleTask;

    public BoundaryFeatureModule(
            IslandBoundaryService boundaryService,
            IslandBoundaryListener boundaryListener,
            SchedulerPort schedulerPort) {
        super(new ModuleDescriptor(
                "boundary",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("island-boundary", "boundary-shield", "virtual-worldborder"),
                ">=1.0.0",
                false));
        this.boundaryService = Objects.requireNonNull(boundaryService, "boundaryService must not be null");
        this.boundaryListener = Objects.requireNonNull(boundaryListener, "boundaryListener must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(IslandBoundaryService.class, boundaryService);
        this.particleTask = schedulerPort.repeatGlobal(
                () -> {
                    for (PlayerUuid uuid : boundaryService.activePerimeterViewers()) {
                        schedulerPort.onEntity(uuid, () -> {
                            Player player = Bukkit.getPlayer(uuid.value());
                            if (player != null && player.isOnline()) {
                                boundaryListener.renderPerimeterForPlayer(player);
                            }
                        });
                    }
                },
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    @Override
    @SuppressWarnings("EmptyCatch")
    protected void onDisable() {
        if (particleTask != null) {
            try {
                particleTask.close();
            } catch (Exception ignored) {
                // Task cancellation failure is non-fatal on disable
            }
            particleTask = null;
        }
    }

    public IslandBoundaryService boundaryService() {
        return boundaryService;
    }
}
