package com.uxplima.uxmskyblock.bukkit.poseidon;

import org.bukkit.block.Block;

/** Gives a chest laid from a template the loot table it is due. */
@FunctionalInterface
public interface ChestLoot {

    /** Gives the container at the block the loot table of that key, rolled with the seed when opened. */
    void give(Block container, String lootTable, long seed);
}
