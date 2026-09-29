package com.uxplima.uxmskyblock.bukkit.stranger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.potion.PotionEffect;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.PotionEffectLines;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.stranger.DistressPalette;

/**
 * What the Upside Down makes of a creature born in it: on a StrangerRealms island's land, a Nether
 * creature is turned into the one of the overworld the operator names, or kept from being born, and
 * every creature born there carries the effects the operator wrote.
 *
 * <p>The Upside Down mirrors the island's land at the same place, so the island a spot in it belongs to
 * is the one at that place in the island's own world. The event runs on the thread of the region that
 * owns the spot, and the creature that replaces another is born there too.
 */
public final class UpsideDownSpawns implements Listener {

    private static final Logger LOGGER = Logger.getLogger(UpsideDownSpawns.class.getName());

    /** The word a rule writes for a creature that is kept from being born. */
    public static final String NONE = "NONE";

    /** Where a creature that replaces another is born. */
    @FunctionalInterface
    public interface Births {

        Entity bear(Location at, EntityType type);
    }

    private final StrangerRealmsService service;
    private final IslandProtectionListener islands;
    private final Supplier<String> upsideDownWorld;
    private final Supplier<List<String>> landWorlds;
    private final Set<CreatureSpawnEvent.SpawnReason> reasons = new HashSet<>();
    private final DistressPalette turn;
    private final List<PotionEffect> effects;
    private final Births births;
    private final java.util.Map<String, Optional<EntityType>> creatures =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * @param landWorlds the worlds islands are made in, where the island a spot in the Upside Down
     *     mirrors is found
     */
    public UpsideDownSpawns(
            StrangerRealmsService service,
            IslandProtectionListener islands,
            StrangerRealmsConfiguration.Mobs mobs,
            Supplier<String> upsideDownWorld,
            Supplier<List<String>> landWorlds,
            Births births) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.upsideDownWorld = Objects.requireNonNull(upsideDownWorld, "upsideDownWorld must not be null");
        this.landWorlds = Objects.requireNonNull(landWorlds, "landWorlds must not be null");
        this.births = Objects.requireNonNull(births, "births must not be null");
        for (String reason : mobs.reasons()) {
            try {
                reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(reason.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                LOGGER.warning(
                        () -> "modules/strangerrealms.conf mobs reasons: the server has no reason " + reason + ".");
            }
        }
        List<String> unread = new ArrayList<>();
        this.turn = DistressPalette.parse(mobs.turn(), unread);
        for (String line : unread) {
            LOGGER.warning(() -> "modules/strangerrealms.conf mobs turn: " + line + " is not FROM:TO.");
        }
        this.effects = PotionEffectLines.allWritten("modules/strangerrealms.conf mobs effects", mobs.effects());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBirth(CreatureSpawnEvent event) {
        if (!reasons.contains(event.getSpawnReason())) {
            return;
        }
        Location at = event.getLocation();
        if (at.getWorld() == null || !at.getWorld().getName().equals(upsideDownWorld.get()) || !onStrangerLand(at)) {
            return;
        }
        String name = event.getEntityType().name();
        String into = turn.distressed(name);
        if (into.equals(name)) {
            corrupt(event.getEntity());
            return;
        }
        if (NONE.equals(into)) {
            event.setCancelled(true);
            return;
        }
        EntityType type =
                creatures.computeIfAbsent(into, UpsideDownSpawns::creatureNamed).orElse(null);
        if (type == null) {
            corrupt(event.getEntity());
            return;
        }
        event.setCancelled(true);
        if (births.bear(at, type) instanceof LivingEntity born) {
            corrupt(born);
        }
    }

    private void corrupt(LivingEntity creature) {
        for (PotionEffect effect : effects) {
            creature.addPotionEffect(effect);
        }
    }

    private boolean onStrangerLand(Location at) {
        for (String world : landWorlds.get()) {
            Optional<Island> island = islands.spatialIndex().findIslandAt(world, at.getBlockX(), at.getBlockZ());
            if (island.isPresent()) {
                return service.isStranger(island.get().id());
            }
        }
        return false;
    }

    /** The creature of that name, read once, with a warning the one time it names none. */
    private static Optional<EntityType> creatureNamed(String name) {
        try {
            EntityType type = EntityType.valueOf(name);
            if (type.isSpawnable() && type.isAlive()) {
                return Optional.of(type);
            }
        } catch (IllegalArgumentException e) {
            // Named below.
        }
        LOGGER.warning(() -> "modules/strangerrealms.conf mobs turn names " + name + ", which is no creature.");
        return Optional.empty();
    }
}
