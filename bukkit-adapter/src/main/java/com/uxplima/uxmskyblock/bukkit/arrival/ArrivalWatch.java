package com.uxplima.uxmskyblock.bukkit.arrival;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Hands every arrival to the same rules, whether the server announced it or not.
 *
 * <p>On Paper a teleport fires {@link PlayerTeleportEvent} before the player moves, and a rule that
 * refuses cancels it. Folia fires nothing for an asynchronous teleport, a {@code /tp}, an ender pearl or
 * a respawn: the quarantine, the bankrupt island, the closed chunk, the pearl out of an island, the
 * border and the visit book all listened for a teleport and heard none. So each player is also looked at
 * on their own thread a few times a second. A walk is explained by its move events; any other change of
 * place is an arrival nobody announced, and a rule that refuses it sends the player back.
 *
 * <p>On a server that does announce teleports the look finds every move already explained, so a rule
 * runs once per arrival on either.
 */
public final class ArrivalWatch implements Listener {

    private static final Logger LOGGER = Logger.getLogger(ArrivalWatch.class.getName());

    /** How often a player is looked at. A teleport shows within a quarter of a second. */
    static final Duration EVERY = Duration.ofMillis(250);

    /** How long after its pearl lands a player's change of place is the pearl's doing. */
    static final Duration PEARL_WINDOW = Duration.ofSeconds(2);

    private final SchedulerPort scheduler;
    private final Supplier<Location> refuge;
    private final Clock clock;
    private final List<ArrivalGate> gates = new CopyOnWriteArrayList<>();
    private final List<ArrivalObserver> observers = new CopyOnWriteArrayList<>();
    private final Map<UUID, Location> lastSeen = new ConcurrentHashMap<>();
    private final Set<UUID> died = ConcurrentHashMap.newKeySet();
    /**
     * Players being sent back from a refused arrival. Their return is no arrival of its own, so no rule
     * may refuse it and leave them standing where they were refused.
     */
    private final Set<UUID> sendingBack = ConcurrentHashMap.newKeySet();
    /** Players who got off something they rode, which carried them without a move event. */
    private final Set<UUID> rode = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Instant> pearlLanded = new ConcurrentHashMap<>();

    /**
     * @param refuge where a player goes whose login or respawn a rule refuses, since there is no place
     *     before it to send them back to
     */
    public ArrivalWatch(SchedulerPort scheduler, Supplier<Location> refuge, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.refuge = Objects.requireNonNull(refuge, "refuge must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Adds a rule every arrival is put to. */
    public void admit(ArrivalGate gate) {
        gates.add(Objects.requireNonNull(gate, "gate must not be null"));
    }

    /** Adds something told of every arrival no rule refused. */
    public void observe(ArrivalObserver observer) {
        observers.add(Objects.requireNonNull(observer, "observer must not be null"));
    }

    /** Starts looking at the players online. Closing the result stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, EVERY, EVERY);
    }

    private void round() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> sample(player));
        }
    }

    /** Looks at one player on their own thread and hands on a change of place nothing explained. */
    void sample(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        UUID id = player.getUniqueId();
        Location here = player.getLocation();
        if (here == null) {
            return;
        }
        Location seen = lastSeen.put(id, here);
        if (seen == null) {
            arrive(new Arrival(player, null, here, ArrivalCause.JOIN, false));
            return;
        }
        if (sameBlock(seen, here)) {
            // A player who came back from death where they fell has arrived nowhere new.
            died.remove(id);
            return;
        }
        arrive(new Arrival(player, seen, here, causeOf(player), false));
    }

    private ArrivalCause causeOf(Player player) {
        UUID id = player.getUniqueId();
        if (died.remove(id)) {
            return ArrivalCause.RESPAWN;
        }
        if (rode.remove(id)) {
            return ArrivalCause.VEHICLE;
        }
        Instant landed = pearlLanded.remove(id);
        if (landed != null && !landed.plus(PEARL_WINDOW).isBefore(clock.instant())) {
            return ArrivalCause.PEARL;
        }
        return player.isInsideVehicle() ? ArrivalCause.VEHICLE : ArrivalCause.TELEPORT;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (sendingBack.remove(event.getPlayer().getUniqueId())) {
            return;
        }
        Arrival arrival = new Arrival(
                event.getPlayer(),
                event.getFrom(),
                event.getTo(),
                event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                        ? ArrivalCause.PEARL
                        : ArrivalCause.TELEPORT,
                true);
        if (refused(arrival)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo().clone();
        lastSeen.put(event.getPlayer().getUniqueId(), to);
        pearlLanded.remove(event.getPlayer().getUniqueId());
        tell(new Arrival(
                event.getPlayer(),
                event.getFrom(),
                to,
                event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                        ? ArrivalCause.PEARL
                        : ArrivalCause.TELEPORT,
                true));
    }

    /**
     * A step that starts somewhere the player was not last seen follows a change of place nothing
     * announced. It is judged here, before the step is recorded, or a teleport followed at once by a
     * step would pass as a walk: the step would explain the place, and the look would find nothing new.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Location seen = lastSeen.get(id);
        Location from = event.getFrom();
        if (seen == null || player.isDead() || sameBlock(seen, from)) {
            return;
        }
        Location here = from.clone();
        lastSeen.put(id, here);
        Arrival arrival = new Arrival(player, seen, here, causeOf(player), false);
        if (refused(arrival)) {
            event.setCancelled(true);
            sendBack(arrival);
            return;
        }
        tell(arrival);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            lastSeen.put(event.getPlayer().getUniqueId(), event.getTo().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location here = player.getLocation();
        if (here == null) {
            return;
        }
        lastSeen.put(player.getUniqueId(), here);
        arrive(new Arrival(player, null, here, ArrivalCause.JOIN, false));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        died.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearl(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player thrower) {
            pearlLanded.put(thrower.getUniqueId(), clock.instant());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(VehicleExitEvent event) {
        if (event.getExited() instanceof Player rider) {
            rode.add(rider.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastSeen.remove(id);
        died.remove(id);
        rode.remove(id);
        sendingBack.remove(id);
        pearlLanded.remove(id);
    }

    private void arrive(Arrival arrival) {
        if (refused(arrival)) {
            sendBack(arrival);
            return;
        }
        tell(arrival);
    }

    private boolean refused(Arrival arrival) {
        for (ArrivalGate gate : gates) {
            try {
                if (gate.refuses(arrival)) {
                    return true;
                }
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "An arrival rule failed for " + arrival.player().getName());
            }
        }
        return false;
    }

    private void tell(Arrival arrival) {
        for (ArrivalObserver observer : observers) {
            try {
                observer.arrived(arrival);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.WARNING,
                        e,
                        () -> "An arrival observer failed for "
                                + arrival.player().getName());
            }
        }
    }

    /**
     * Undoes an arrival that already happened: back to where the player was, or to the refuge when
     * they logged in or came back from death and there is no earlier place that was theirs.
     */
    private void sendBack(Arrival arrival) {
        Player player = arrival.player();
        Location from = arrival.from();
        Location back = from == null || arrival.cause() == ArrivalCause.RESPAWN || arrival.cause() == ArrivalCause.JOIN
                ? refuge.get()
                : from;
        if (player.isInsideVehicle()) {
            player.leaveVehicle();
        }
        lastSeen.put(player.getUniqueId(), back.clone());
        sendingBack.add(player.getUniqueId());
        var unused =
                player.teleportAsync(back).whenComplete((moved, failed) -> sendingBack.remove(player.getUniqueId()));
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ()
                && Objects.equals(a.getWorld(), b.getWorld());
    }
}
