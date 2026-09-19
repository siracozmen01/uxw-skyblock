package com.uxplima.uxmskyblock.bukkit.ward;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
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

        triggerKineticWave(dest);
    }

    public void triggerKineticWave(Location center) {
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

        try {
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.2f);
            world.spawnParticle(Particle.CRIT, center.clone().add(0, 0.5, 0), 20, 0.8, 0.2, 0.8, 0.1);
        } catch (Exception ignored) {
            // MockBukkit or minimal server fallback
        }
    }

    public KineticWardService wardService() {
        return wardService;
    }
}
