package com.uxplima.uxmskyblock.bukkit.protection;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;

/**
 * Anti-Exploit Obsidian Recovery Listener (Section 2.42 item 1).
 * Tracks accidental cobblestone generator water-lava collision forming obsidian via transient timestamps (60s TTL).
 * Allows recovering lava into empty buckets with sound cues and smoke particle bursts,
 * while preventing placed obsidian dupe exploits.
 */
public final class ObsidianRecoveryListener implements Listener {

    /**
     * What this interaction fires, as the operator wrote it.
     *
     * <p>A sound and a particle were written into this file. A node built without a list fires
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

    private final ProtectionConfiguration config;
    private final Clock clock;
    private final Map<Location, Instant> accidentalObsidian = new ConcurrentHashMap<>();

    public ObsidianRecoveryListener(ProtectionConfiguration config) {
        this(config, Clock.systemUTC());
    }

    public ObsidianRecoveryListener(ProtectionConfiguration config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (!config.obsidianRecoveryEnabled()) {
            return;
        }

        if (event.getNewState().getType() == Material.OBSIDIAN) {
            accidentalObsidian.put(event.getBlock().getLocation(), Instant.now(clock));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!config.obsidianRecoveryEnabled()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.OBSIDIAN) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BUCKET) {
            return;
        }

        Location loc = clickedBlock.getLocation();
        Instant formedAt = accidentalObsidian.get(loc);

        if (config.obsidianRecoveryAccidentalOnly()) {
            if (formedAt == null) {
                return;
            }
            Instant expiry = formedAt.plus(config.obsidianRecoveryExpiration());
            if (Instant.now(clock).isAfter(expiry)) {
                accidentalObsidian.remove(loc);
                return;
            }
        }

        // Successfully recovering accidental obsidian to lava
        event.setCancelled(true);
        accidentalObsidian.remove(loc);
        clickedBlock.setType(Material.AIR);

        Player player = event.getPlayer();
        updateBucket(player, item);

        if (loc.getWorld() != null) {
            com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
            com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
            if (written != null && plays != null) {
                plays.fire(written, "obsidian-recovery", player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.OBSIDIAN) {
            accidentalObsidian.remove(event.getBlock().getLocation());
        }
    }

    private void updateBucket(Player player, ItemStack bucketItem) {
        if (bucketItem.getAmount() == 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.LAVA_BUCKET));
        } else {
            bucketItem.setAmount(bucketItem.getAmount() - 1);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(Material.LAVA_BUCKET));
            if (!overflow.isEmpty() && player.getWorld() != null) {
                for (ItemStack dropped : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }
            }
        }
    }

    public Map<Location, Instant> accidentalObsidianMap() {
        return accidentalObsidian;
    }
}
