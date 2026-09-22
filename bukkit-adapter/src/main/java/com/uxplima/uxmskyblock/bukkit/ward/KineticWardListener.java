package com.uxplima.uxmskyblock.bukkit.ward;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService;
import com.uxplima.uxmskyblock.core.application.ward.KineticWardService.TargetEntity;
import com.uxplima.uxmskyblock.core.domain.ward.KineticRepulsionResult;
import com.uxplima.uxmskyblock.core.domain.ward.KineticRepulsionResult.RepulsedEntity;

/**
 * Enterprise Kinetic Ward Listener (Section 2.42 item 4).
 * Intercepts teleport arrivals and emits a non-destructive 5-block repulsive wave pushing hostile entities
 * away from the arrival coordinates with particle and sound cues, eliminating despawn anti-patterns.
 */
public final class KineticWardListener implements Listener {

    private final ProtectionConfiguration config;
    private final KineticWardService wardService;

    /**
     * What this interaction fires, as the operator wrote it.
     *
     * <p>A sound and a particle were written into this file, so a server that wanted a different
     * note, or none, or a title as well, had nowhere to say so. A node built without one fires
     * nothing, which is the same thing said in the file as an empty list.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffects
            effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffectPlayer
            effectPlayer;

    /** Tells this rule what the operator wrote for it. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@org.jspecify.annotations.Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    public KineticWardListener(ProtectionConfiguration config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.wardService = new KineticWardService(
                config.kineticWardRadius(), config.kineticWardForce(), config.kineticWardVerticalLift());
    }

    public KineticWardListener(ProtectionConfiguration config, KineticWardService wardService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.wardService = Objects.requireNonNull(wardService, "wardService must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!config.kineticWardEnabled()) {
            return;
        }

        Location dest = event.getTo();
        if (dest == null || dest.getWorld() == null) {
            return;
        }

        triggerKineticWave(dest, event.getPlayer());
    }

    /** The wave with nobody to show it to, for a caller that only has a place. */
    public void triggerKineticWave(Location center) {
        triggerKineticWave(center, null);
    }

    public void triggerKineticWave(Location center, org.bukkit.entity.@org.jspecify.annotations.Nullable Player who) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        double radius = config.kineticWardRadius();
        List<Entity> nearbyEntities = new ArrayList<>(world.getNearbyEntities(center, radius, radius, radius));

        List<TargetEntity> targets = new ArrayList<>();
        List<Entity> matchedBukkitEntities = new ArrayList<>();

        for (Entity e : nearbyEntities) {
            // Repulse only hostile mobs, never repulse tamed pets or players
            if (e instanceof Monster) {
                if (e instanceof Tameable tameable && tameable.isTamed()) {
                    continue;
                }
                targets.add(new TargetEntity(
                        e.getUniqueId().toString(),
                        e.getLocation().getX(),
                        e.getLocation().getY(),
                        e.getLocation().getZ()));
                matchedBukkitEntities.add(e);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        KineticRepulsionResult result =
                wardService.calculateRepulsion(center.getX(), center.getY(), center.getZ(), targets);

        for (int i = 0; i < result.repulsedEntities().size(); i++) {
            RepulsedEntity rep = result.repulsedEntities().get(i);
            Entity bukkitEntity = matchedBukkitEntities.get(i);
            bukkitEntity.setVelocity(new Vector(rep.velocityX(), rep.velocityY(), rep.velocityZ()));
        }

        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        // The player who just arrived is who this happened to, so they are who hears it. An
        // audience left empty renders every line and drops it.
        if (written != null && plays != null && who != null) {
            plays.fire(written, "kinetic-ward", who);
        }
    }

    public KineticWardService wardService() {
        return wardService;
    }
}
