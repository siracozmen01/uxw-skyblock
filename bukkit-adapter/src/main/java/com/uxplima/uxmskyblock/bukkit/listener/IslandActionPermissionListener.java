package com.uxplima.uxmskyblock.bukkit.listener;

import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.domain.island.IslandPermission;

/**
 * The island permissions the protection rules never asked for.
 *
 * <p>The role editor publishes a permission for filling a bucket, for breaking a spawner, for
 * changing what one spawns, for breeding an animal, for killing one and for walking over a crop.
 * Not one of them was read anywhere, so an operator could take any of them off a role, the editor
 * would show it taken off, and the member went on doing it.
 *
 * <p>Every rule here asks {@link IslandProtectionListener#mayDoHere} and nothing else. That gate
 * answers off the caches the protection listener already keeps, so none of this reaches the
 * database on the thread an event arrives on, which is what Folia requires.
 *
 * <p>Upgrading a spawner is the one permission with no rule here, because this plugin has no
 * spawner upgrade to gate: the permission and its key exist and nothing else does.
 */
public final class IslandActionPermissionListener implements Listener {

    private final IslandProtectionListener gate;
    private final Messages messages;

    public IslandActionPermissionListener(IslandProtectionListener gate, Messages messages) {
        this.gate = Objects.requireNonNull(gate, "gate must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Emptying a bucket is placing what is in it, and filling one is taking what is there. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        refuse(event, event.getPlayer(), block.getLocation(), IslandPermission.BUCKET_USE, "protection.bucket_denied");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        refuse(event, event.getPlayer(), block.getLocation(), IslandPermission.BUCKET_USE, "protection.bucket_denied");
    }

    /**
     * Breaking a spawner asks for more than breaking a block.
     *
     * <p>A spawner is the most valuable block an island holds, which is why the role editor names
     * it on its own. The block rule runs too and asks for the block permission; this asks for the
     * spawner one, and a role needs both.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) {
            return;
        }
        refuse(
                event,
                event.getPlayer(),
                event.getBlock().getLocation(),
                IslandPermission.SPAWNER_BREAK,
                "protection.spawner_break_denied");
    }

    /** A spawn egg on a spawner changes what it spawns, and that has its own permission. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawnerRetype(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || clicked == null) {
            return;
        }
        if (clicked.getType() != Material.SPAWNER || !isSpawnEgg(event.getItem())) {
            return;
        }
        refuse(
                event,
                event.getPlayer(),
                clicked.getLocation(),
                IslandPermission.SPAWNER_CHANGE_TYPE,
                "protection.spawner_change_denied");
    }

    /**
     * Walking over farmland turns it back to dirt and takes the crop with it.
     *
     * <p>The permission is named for the bypass rather than the act: holding it is permission to
     * trample. A role without it walks over a field without ruining it, which is what an island
     * that farms wants from everyone it lets in.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropTrample(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (event.getAction() != Action.PHYSICAL || clicked == null || clicked.getType() != Material.FARMLAND) {
            return;
        }
        refuse(
                event,
                event.getPlayer(),
                clicked.getLocation(),
                IslandPermission.CROP_TRAMPLE_BYPASS,
                "protection.crop_trample_denied");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnimalBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) {
            return;
        }
        refuse(
                event,
                player,
                event.getEntity().getLocation(),
                IslandPermission.ANIMAL_BREED,
                "protection.animal_breed_denied");
    }

    /** Hitting an animal is killing it, sooner or later, so the rule is on the hit. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnimalDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof Animals)) {
            return;
        }
        refuse(
                event,
                player,
                event.getEntity().getLocation(),
                IslandPermission.ANIMAL_KILL,
                "protection.animal_kill_denied");
    }

    private static boolean isSpawnEgg(ItemStack held) {
        return held != null && held.getType().name().endsWith("_SPAWN_EGG");
    }

    private void refuse(
            Cancellable event, Player player, Location location, IslandPermission permission, String refusalKey) {
        IslandProtectionListener.Verdict verdict = gate.mayDoHere(player, location, permission);
        if (verdict == IslandProtectionListener.Verdict.ALLOWED) {
            return;
        }
        event.setCancelled(true);
        player.sendMessage(messages.render(
                player, verdict == IslandProtectionListener.Verdict.FROZEN ? "protection.frozen" : refusalKey));
    }
}
