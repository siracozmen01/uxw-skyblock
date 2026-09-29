package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import com.uxplima.uxmskyblock.bukkit.config.PoseidonConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.effect.PotionEffectLines;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.poseidon.PoseidonService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.hazard.PoseidonExposure;
import com.uxplima.uxmskyblock.core.domain.hazard.PoseidonRules;
import com.uxplima.uxmskyblock.core.domain.hazard.Stillness;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;

/**
 * Water is the air of a Poseidon island: each player standing on one is checked on a fixed beat. In
 * the water they are given what the operator wrote, and hurt only once they have stayed still too long.
 * Out of it, dry air hurts and the sun hurts more, unless the rain keeps them wet.
 *
 * <p>The beat runs on the global thread and only hands each player to their own thread, where the
 * check reads the blocks around them. Every answer about the island comes from memory.
 */
public final class PoseidonHazard {

    /** A day is 24000 ticks and the sun is up for the first half of it. */
    private static final long DAY_TICKS = 24_000;

    private static final long SUNSET = 12_000;

    private final PoseidonService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final PoseidonRules rules;
    private final List<PotionEffect> waterEffects;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;
    private final Clock clock;
    private final Map<UUID, Stillness> rests = new ConcurrentHashMap<>();

    public PoseidonHazard(
            PoseidonService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            PoseidonConfiguration config,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer,
            Clock clock) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.rules = config.rules();
        this.waterEffects =
                PotionEffectLines.allWritten("modules/poseidon.conf hazard water-effects", config.waterEffects());
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, rules.checkEvery(), rules.checkEvery());
    }

    private void round() {
        Set<UUID> online = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> check(player));
        }
        // A player who left is no longer resting anywhere.
        rests.keySet().retainAll(online);
    }

    /** One check of one player, on the player's own thread. Returns the health it took. */
    public double check(Player player) {
        if (!player.isValid() || player.isDead()) {
            return 0;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            rests.remove(player.getUniqueId());
            return 0;
        }
        Location feet = player.getLocation();
        if (feet == null) {
            return 0;
        }
        Optional<Island> island = islands.findIslandAt(feet);
        if (island.isEmpty() || !service.isPoseidon(island.get().id())) {
            rests.remove(player.getUniqueId());
            return 0;
        }
        PoseidonExposure exposure = exposureOf(player, feet);
        if (exposure.inWater()) {
            for (PotionEffect effect : waterEffects) {
                player.addPotionEffect(effect);
            }
        }
        PoseidonRules.Harm harm = rules.harm(exposure);
        double taken = rules.damage(exposure);
        if (taken <= 0) {
            return 0;
        }
        player.damage(taken);
        switch (harm) {
            case STILL -> effectPlayer.fire(effects, "poseidon-still-drowns", player);
            case SUN -> effectPlayer.fire(effects, "poseidon-sun-burns", player);
            case DRY -> effectPlayer.fire(effects, "poseidon-dry-air", player);
            case NONE -> {}
        }
        return taken;
    }

    private PoseidonExposure exposureOf(Player player, Location feet) {
        Block eyeBlock = player.getEyeLocation().getBlock();
        boolean inWater = isWater(feet.getBlock()) || isWater(eyeBlock);
        World world = player.getWorld();
        boolean underSky = world.getHighestBlockYAt(feet) < eyeBlock.getY();
        boolean rainedOn = underSky && world.hasStorm();
        boolean inSun = underSky
                && !world.hasStorm()
                && world.getEnvironment() == World.Environment.NORMAL
                && Math.floorMod(world.getTime(), DAY_TICKS) < SUNSET;
        Duration stillFor = Duration.ZERO;
        if (inWater) {
            UUID id = player.getUniqueId();
            java.time.Instant now = clock.instant();
            Stillness rest = rests.compute(
                    id,
                    (key, held) -> held == null
                            ? Stillness.at(feet.getX(), feet.getY(), feet.getZ(), now)
                            : held.seenAt(feet.getX(), feet.getY(), feet.getZ(), now, rules.stillReach()));
            stillFor = rest.heldFor(now);
        } else {
            rests.remove(player.getUniqueId());
        }
        return new PoseidonExposure(inWater, rainedOn, inSun, stillFor);
    }

    private static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }
}
