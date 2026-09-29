package com.uxplima.uxmskyblock.bukkit.acid;

import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * A bottle of water drunk on an AcidIsland island burns unless it was made clean, and a bottle filled
 * from a cauldron there comes out clean, because a cauldron holds the rain.
 *
 * <p>Both handlers run on the thread that owns the player and the block, so nothing here is scheduled.
 */
public final class AcidWaterListener implements Listener {

    private final AcidHazard hazard;
    private final Messages messages;

    public AcidWaterListener(AcidHazard hazard, Messages messages) {
        this.hazard = Objects.requireNonNull(hazard, "hazard must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!AcidWater.isAcid(event.getItem()) || !hazard.onAcidIsland(player.getLocation())) {
            return;
        }
        hazard.drank(player);
        messages.send(player, "acid.drank");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFillFromCauldron(CauldronLevelChangeEvent event) {
        if (event.getReason() != CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Block cauldron = event.getBlock();
        if (!hazard.onAcidIsland(cauldron.getLocation())) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        EquipmentSlot hand = inventory.getItemInMainHand().getType() == Material.GLASS_BOTTLE
                ? EquipmentSlot.HAND
                : inventory.getItemInOffHand().getType() == Material.GLASS_BOTTLE ? EquipmentSlot.OFF_HAND : null;
        if (hand == null) {
            return;
        }
        // The server would hand over a plain bottle, which is acid here. The same fill is done by hand
        // instead, with a clean bottle.
        event.setCancelled(true);
        event.getNewState().update(true, false);
        ItemStack bottles = inventory.getItem(hand);
        if (bottles.getAmount() <= 1) {
            inventory.setItem(hand, AcidWater.cleanBottle());
        } else {
            bottles.setAmount(bottles.getAmount() - 1);
            inventory.setItem(hand, bottles);
            for (ItemStack left : inventory.addItem(AcidWater.cleanBottle()).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
        }
        hazard.filledClean(player);
    }
}
