package com.uxplima.uxmskyblock.bukkit.boxed;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.boxed.BoxedService;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The creation action that makes an island a Boxed island: it records the island and shrinks its edge
 * to the starting box. The land itself is the world's own, so nothing is built.
 *
 * <p>Recording is a write, so it is done off the region's thread, and the edge is moved once it is.
 */
public final class BoxedStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:boxed";

    private static final Logger LOGGER = Logger.getLogger(BoxedStart.class.getName());

    private final BoxedService service;
    private final SchedulerPort scheduler;
    private final Consumer<IslandId> boxChanged;

    public BoxedStart(BoxedService service, SchedulerPort scheduler, Consumer<IslandId> boxChanged) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.boxChanged = Objects.requireNonNull(boxChanged, "boxChanged must not be null");
    }

    @Override
    public String actionId() {
        return ACTION;
    }

    @Override
    public void apply(IslandStart start) {
        IslandId islandId = start.islandId();
        scheduler.async(() -> {
            try {
                service.start(islandId);
                boxChanged.accept(islandId);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be made a Boxed island.");
            }
        });
    }
}
