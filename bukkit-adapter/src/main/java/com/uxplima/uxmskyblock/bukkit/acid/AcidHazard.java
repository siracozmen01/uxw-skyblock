package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.uxplima.uxmskyblock.bukkit.config.AcidIslandConfiguration;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer;
import com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.acid.AcidIslandService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidExposure;
import com.uxplima.uxmskyblock.core.domain.hazard.AcidRules;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * The acid sea and the acid rain: each player standing on an AcidIsland island is checked on a fixed
 * beat, and whatever reaches them hurts.
 *
 * <p>The beat runs on the global thread and only hands each player to their own thread, where the
 * check reads the blocks around them. On Folia a player's blocks belong to the player's region, so
 * nothing about the player is read anywhere else. Every answer about the island comes from memory.
 */
public final class AcidHazard {

    private static final Logger LOGGER = Logger.getLogger(AcidHazard.class.getName());

    private final AcidIslandService service;
    private final IslandProtectionListener islands;
    private final SchedulerPort scheduler;
    private final AcidRules rules;
    private final List<PotionEffectType> protection;
    private final List<PotionEffect> seaEffects;
    private final InteractionEffects effects;
    private final InteractionEffectPlayer effectPlayer;

    public AcidHazard(
            AcidIslandService service,
            IslandProtectionListener islands,
            SchedulerPort scheduler,
            AcidIslandConfiguration config,
            InteractionEffects effects,
            InteractionEffectPlayer effectPlayer) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.islands = Objects.requireNonNull(islands, "islands must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.rules = config.rules();
        this.effects = Objects.requireNonNull(effects, "effects must not be null");
        this.effectPlayer = Objects.requireNonNull(effectPlayer, "effectPlayer must not be null");
        this.protection = new ArrayList<>();
        for (String name : config.waterProtection()) {
            PotionEffectType type = effectNamed(name);
            if (type == null) {
                LOGGER.warning(() -> "modules/acidisland.conf water-protection: no effect is named " + name + ".");
            } else {
                protection.add(type);
            }
        }
        this.seaEffects = new ArrayList<>();
        for (String written : config.waterEffects()) {
            PotionEffect effect = effectWritten(written);
            if (effect == null) {
                LOGGER.warning(() -> "modules/acidisland.conf water-effects: " + written
                        + " is not name:amplifier:seconds with an effect of that name.");
            } else {
                seaEffects.add(effect);
            }
        }
    }

    /** Starts the beat. Closing what it returns stops it. */
    public AutoCloseable start() {
        return scheduler.repeatGlobal(this::round, rules.checkEvery(), rules.checkEvery());
    }

    private void round() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scheduler.onEntity(PlayerUuid.of(player.getUniqueId()), () -> check(player));
        }
    }

    /** One check of one player, on the player's own thread. Returns the health it took. */
    public double check(Player player) {
        if (!player.isValid() || player.isDead()) {
            return 0;
        }
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return 0;
        }
        Location feet = player.getLocation();
        if (feet == null) {
            return 0;
        }
        Optional<Island> island = islands.findIslandAt(feet);
        if (island.isEmpty() || !service.isAcid(island.get().id())) {
            return 0;
        }
        AcidExposure exposure = exposureOf(player, feet);
        double taken = rules.damage(exposure);
        if (taken <= 0) {
            return 0;
        }
        player.damage(taken);
        if (rules.seaHurts(exposure)) {
            for (PotionEffect effect : seaEffects) {
                player.addPotionEffect(effect);
            }
            effectPlayer.fire(effects, "acid-sea-burns", player);
        } else {
            effectPlayer.fire(effects, "acid-rain-burns", player);
        }
        return taken;
    }

    private AcidExposure exposureOf(Player player, Location feet) {
        Block feetBlock = feet.getBlock();
        Block eyeBlock = player.getEyeLocation().getBlock();
        boolean inWater = isWater(feetBlock) || isWater(eyeBlock);
        World world = player.getWorld();
        boolean rainedOn = world.hasStorm() && world.getHighestBlockYAt(feet) < eyeBlock.getY();
        ItemStack helmet = player.getInventory().getHelmet();
        boolean wearsHelmet = helmet != null && !helmet.isEmpty();
        boolean safe = false;
        for (PotionEffectType type : protection) {
            if (player.hasPotionEffect(type)) {
                safe = true;
                break;
            }
        }
        return new AcidExposure(inWater, rainedOn, wearsHelmet, safe);
    }

    private static boolean isWater(Block block) {
        Material type = block.getType();
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return true;
        }
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private static @Nullable PotionEffectType effectNamed(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.trim().toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.MOB_EFFECT.get(key);
    }

    /** An effect written {@code name:amplifier:seconds}, or null when it cannot be read. */
    static @Nullable PotionEffect effectWritten(String written) {
        String[] parts = written.trim().split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        PotionEffectType type = effectNamed(parts[0]);
        if (type == null) {
            return null;
        }
        try {
            int amplifier = Integer.parseInt(parts[1].trim());
            int seconds = Integer.parseInt(parts[2].trim());
            if (amplifier < 0 || seconds < 1) {
                return null;
            }
            return new PotionEffect(type, seconds * 20, amplifier);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
