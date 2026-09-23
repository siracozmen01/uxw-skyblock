package com.uxplima.uxmskyblock.bukkit.protection;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise Zero-Velocity Void Recovery & Kinetic Shield Listener (Section 2.41 & Section 2.42 item 2).
 * Resets falling player velocity vector to (0,0,0), clears accumulated fall distance,
 * safely teleports to island anchor, and grants 10-second fall damage and PvP invulnerability shields.
 */
public final class VoidProtectionListener implements Listener {

    private final ProtectionConfiguration protectionConfig;
    private final SettingsConfiguration settingsConfig;
    private final Function<Location, Optional<Island>> islandLookup;
    private final Clock clock;

    private final Map<UUID, Instant> fallDamageShields = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> pvpInvulnerabilityShields = new ConcurrentHashMap<>();

    /**
     * Players whose move home is under way. The move is asynchronous, so the player keeps falling
     * for a tick or more after it is asked for, and every move and void hit in that time asked again:
     * another teleport, another message and another pair of shields for the same fall.
     */
    private final java.util.Set<UUID> beingRescued = ConcurrentHashMap.newKeySet();

    private final Messages messages;

    public VoidProtectionListener(
            ProtectionConfiguration protectionConfig,
            SettingsConfiguration settingsConfig,
            Function<Location, Optional<Island>> islandLookup) {
        this(protectionConfig, settingsConfig, islandLookup, Clock.systemUTC(), Messages.bundled());
    }

    public VoidProtectionListener(
            ProtectionConfiguration protectionConfig,
            SettingsConfiguration settingsConfig,
            Function<Location, Optional<Island>> islandLookup,
            Clock clock,
            Messages messages) {
        this.protectionConfig = Objects.requireNonNull(protectionConfig, "protectionConfig must not be null");
        this.settingsConfig = Objects.requireNonNull(settingsConfig, "settingsConfig must not be null");
        this.islandLookup = Objects.requireNonNull(islandLookup, "islandLookup must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.VOID || !(event.getEntity() instanceof Player player)) {
            return;
        }

        if (handleVoidRecovery(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!protectionConfig.voidRecoveryEnabled()) {
            return;
        }
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }

        if (to.getY() < protectionConfig.voidRecoveryThresholdY()) {
            Player player = event.getPlayer();
            handleVoidRecovery(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }

        Instant shieldUntil = fallDamageShields.get(player.getUniqueId());
        if (shieldUntil != null) {
            if (Instant.now(clock).isBefore(shieldUntil)) {
                event.setCancelled(true);
            } else {
                fallDamageShields.remove(player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Instant pvpShieldUntil = pvpInvulnerabilityShields.get(victim.getUniqueId());
        if (pvpShieldUntil != null) {
            if (Instant.now(clock).isBefore(pvpShieldUntil)) {
                event.setCancelled(true);
                if (event.getDamager() instanceof Player damager) {
                    damager.sendMessage(messages.render(damager, "protection.void_pvp_immune"));
                }
            } else {
                pvpInvulnerabilityShields.remove(victim.getUniqueId());
            }
        }
    }

    public boolean handleVoidRecovery(Player player) {
        if (!protectionConfig.voidRecoveryEnabled()) {
            return false;
        }

        Location loc = player.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return false;
        }

        Optional<Island> optIsland = islandLookup.apply(loc);
        boolean isMember = optIsland.map(island -> isMember(island, player)).orElse(false);

        if (isMember && !settingsConfig.voidTeleportMembers()) {
            return false;
        }
        if (!isMember && !settingsConfig.voidTeleportVisitors()) {
            return false;
        }

        // The island's surface is not read here. This runs on the falling player's region, and on
        // Folia the island's centre may be another region's chunk or no loaded chunk at all, so
        // reading it threw and the player fell to their death. The player is moved over the centre
        // first, and the surface is read once they are there, where the chunk is theirs.
        World world = loc.getWorld();
        Location destination;
        @Nullable Location centre = null;
        if (optIsland.isPresent()) {
            Island island = optIsland.get();
            centre = new Location(
                    world,
                    island.bounds().centerX() + 0.5,
                    world.getMaxHeight() - 1,
                    island.bounds().centerZ() + 0.5);
            destination = centre;
        } else {
            destination = world.getSpawnLocation();
        }

        // Zero-velocity reset & clear fall distance to prevent carryover deaths
        player.setVelocity(new Vector(0, 0, 0));
        player.setFallDistance(0.0f);
        UUID playerId = player.getUniqueId();
        if (!beingRescued.add(playerId)) {
            // Already on the way home. The void still lands no hit.
            return true;
        }
        // Asynchronous, because Folia refuses a synchronous teleport outright: it threw here, so a
        // player who fell off an island on a region threaded server fell to their death anyway.
        try {
            @Nullable Location overTheCentre = centre;
            var unused = player.teleportAsync(destination)
                    .thenCompose(moved -> moved && overTheCentre != null
                            ? player.teleportAsync(onTheSurface(overTheCentre))
                            : CompletableFuture.completedFuture(moved))
                    .whenComplete((moved, error) -> beingRescued.remove(playerId));
        } catch (RuntimeException e) {
            beingRescued.remove(playerId);
            throw e;
        }

        // Grant 10-second fall damage shield
        Instant now = Instant.now(clock);
        fallDamageShields.put(player.getUniqueId(), now.plus(protectionConfig.voidRecoveryFallDamageShield()));

        // Grant PvP invulnerability shield if enabled
        if (settingsConfig.immuneToPvpWhenTeleport()) {
            pvpInvulnerabilityShields.put(player.getUniqueId(), now.plus(settingsConfig.pvpTeleportInvulnerability()));
        }

        player.sendMessage(messages.render(player, "protection.void_recovery"));

        return true;
    }

    /** Where to stand over {@code centre}: on its highest block, and never lower than y 65. */
    private static Location onTheSurface(Location centre) {
        World world = centre.getWorld();
        int surface = world.getHighestBlockYAt(centre.getBlockX(), centre.getBlockZ());
        return new Location(world, centre.getX(), Math.max(65, surface + 1), centre.getZ());
    }

    public void grantTeleportInvulnerability(Player player) {
        if (settingsConfig.immuneToPvpWhenTeleport()) {
            pvpInvulnerabilityShields.put(
                    player.getUniqueId(), Instant.now(clock).plus(settingsConfig.pvpTeleportInvulnerability()));
        }
    }

    private boolean isMember(Island island, Player player) {
        if (island.ownerPlayerUuid().value().equals(player.getUniqueId())) {
            return true;
        }
        return island.members().values().stream()
                .anyMatch(m -> m.playerUuid().value().equals(player.getUniqueId()));
    }

    public Map<UUID, Instant> fallDamageShields() {
        return fallDamageShields;
    }

    public Map<UUID, Instant> pvpInvulnerabilityShields() {
        return pvpInvulnerabilityShields;
    }
}
