package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.stranger.NamePattern;
import org.jspecify.annotations.Nullable;

/**
 * The glimmer: a light set on a StrangerRealms island's land shines through at the same place in the
 * Upside Down as light without a block, and one set in the Upside Down shines through on the land. It
 * goes out when the light is broken.
 *
 * <p>The event runs on the thread that owns the light, and the glimmer is set on the thread of the
 * region that owns the same place in the other world.
 */
public final class Glimmer implements Listener {

    private static final Logger LOGGER = Logger.getLogger(Glimmer.class.getName());

    private final StrangerRealmsService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final Supplier<String> upsideDownWorld;
    private final Supplier<List<String>> landWorlds;
    private final List<NamePattern> lights = new ArrayList<>();
    private final int level;

    public Glimmer(
            StrangerRealmsService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            StrangerRealmsConfiguration.Glimmer glimmer,
            Supplier<String> upsideDownWorld,
            Supplier<List<String>> landWorlds) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.upsideDownWorld = Objects.requireNonNull(upsideDownWorld, "upsideDownWorld must not be null");
        this.landWorlds = Objects.requireNonNull(landWorlds, "landWorlds must not be null");
        this.level = glimmer.level();
        for (String written : glimmer.lights()) {
            try {
                lights.add(new NamePattern(written));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(() -> "modules/strangerrealms.conf glimmer lights: " + written + " is no pattern.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLight(BlockPlaceEvent event) {
        Block light = event.getBlockPlaced();
        if (isLight(light.getType())) {
            across(light, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDark(BlockBreakEvent event) {
        Block light = event.getBlock();
        if (isLight(light.getType())) {
            across(light, false);
        }
    }

    private boolean isLight(Material type) {
        String name = type.name();
        for (NamePattern pattern : lights) {
            if (pattern.matches(name)) {
                return true;
            }
        }
        return false;
    }

    private void across(Block light, boolean lit) {
        String other = otherWorld(light.getWorld().getName(), light.getX(), light.getZ());
        if (other == null) {
            return;
        }
        int x = light.getX();
        int y = light.getY();
        int z = light.getZ();
        scheduler.onRegion(other, x >> 4, z >> 4, () -> {
            World world = Bukkit.getWorld(other);
            if (world == null || y < world.getMinHeight() || y >= world.getMaxHeight()) {
                return;
            }
            Block there = world.getBlockAt(x, y, z);
            if (lit && there.getType().isAir()) {
                there.setBlockData(glimmer(), false);
            } else if (!lit && there.getType() == Material.LIGHT) {
                there.setType(Material.AIR, false);
            }
        });
    }

    private BlockData glimmer() {
        BlockData data = Material.LIGHT.createBlockData();
        if (data instanceof Levelled levelled) {
            levelled.setLevel(Math.min(level, levelled.getMaximumLevel()));
        }
        return data;
    }

    /** The world the light glimmers into: the Upside Down from the land, the land from the Upside Down. */
    private @Nullable String otherWorld(String world, int x, int z) {
        String upsideDown = upsideDownWorld.get();
        if (world.equals(upsideDown)) {
            for (String land : landWorlds.get()) {
                Optional<Island> island = islands.spatialIndex().findIslandAt(land, x, z);
                if (island.isPresent()) {
                    return service.isStranger(island.get().id()) ? land : null;
                }
            }
            return null;
        }
        if (!landWorlds.get().contains(world)) {
            return null;
        }
        Optional<Island> island = islands.spatialIndex().findIslandAt(world, x, z);
        return island.isPresent() && service.isStranger(island.get().id()) ? upsideDown : null;
    }
}
