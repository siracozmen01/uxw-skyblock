package com.uxplima.uxmskyblock.bukkit.parkour;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.block.Block;

import com.uxplima.uxmskyblock.bukkit.config.ParkourConfiguration;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandStart;
import com.uxplima.uxmskyblock.core.application.gamemode.CreationActionProvider;
import com.uxplima.uxmskyblock.core.application.parkour.ParkourService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;

/**
 * The creation action that makes an island a Parkour course: records it and sets its start plate at the
 * edge of the platform, where the team builds the course out from.
 */
public final class CourseStart implements CreationActionProvider<IslandStart> {

    /** The name a preset's start list writes for this action. */
    public static final String ACTION = "uxm:course";

    /** How far from the centre, along x, the start plate is set. */
    static final int START_OFFSET = 2;

    private static final Logger LOGGER = Logger.getLogger(CourseStart.class.getName());

    private final ParkourService service;
    private final SchedulerPort scheduler;
    private final ParkourConfiguration.Markers markers;

    public CourseStart(ParkourService service, SchedulerPort scheduler, ParkourConfiguration.Markers markers) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.markers = Objects.requireNonNull(markers, "markers must not be null");
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
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "Island " + islandId + " could not be made a Parkour course.");
            }
        });
        Block under = start.world().getBlockAt(start.centerX() - START_OFFSET, start.y(), start.centerZ());
        Block plate = under.getRelative(0, 1, 0);
        if (!plate.getType().isAir()) {
            return;
        }
        under.setType(markers.startUnder(), false);
        plate.setType(markers.plate(), false);
    }
}
