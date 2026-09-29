package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.Lootable;

/** Loot tables the server knows: the game's own, and any a datapack adds. */
public final class ServerChestLoot implements ChestLoot {

    private static final Logger LOGGER = Logger.getLogger(ServerChestLoot.class.getName());

    @Override
    public void give(Block container, String lootTable, long seed) {
        NamespacedKey key = NamespacedKey.fromString(lootTable);
        LootTable table = key == null ? null : Bukkit.getLootTable(key);
        if (table == null) {
            LOGGER.warning(
                    () -> "modules/poseidon.conf names the loot table '" + lootTable + "', which the server has not.");
            return;
        }
        BlockState state = container.getState();
        if (state instanceof Lootable lootable) {
            lootable.setLootTable(table, seed);
            state.update(true, false);
        }
    }
}
