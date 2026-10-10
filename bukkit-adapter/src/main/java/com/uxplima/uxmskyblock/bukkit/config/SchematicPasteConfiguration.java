package com.uxplima.uxmskyblock.bukkit.config;

import com.uxplima.uxmlib.schematic.paper.PasteOptions;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * How a schematic is pasted: a preset's island and an admin's paste alike.
 *
 * @param blocksPerTick how many positions one chunk of a paste works through in a tick
 * @param chunksAtOnce how many chunks a paste works at once
 * @param pasteAir whether the schematic's air clears what stands where it lands
 * @param entities whether the entities saved in it are made
 * @param biomes whether the biomes saved in it are set
 */
public record SchematicPasteConfiguration(
        int blocksPerTick, int chunksAtOnce, boolean pasteAir, boolean entities, boolean biomes) {

    public static final SchematicPasteConfiguration DEFAULT =
            new SchematicPasteConfiguration(2048, 4, false, true, true);

    public SchematicPasteConfiguration {
        blocksPerTick = Math.max(1, blocksPerTick);
        chunksAtOnce = Math.max(1, chunksAtOnce);
    }

    /** The {@code paste} block of {@code presets}, each value its default where the file says none. */
    public static SchematicPasteConfiguration load(ConfigurationNode paste) {
        if (paste.virtual()) {
            return DEFAULT;
        }
        return new SchematicPasteConfiguration(
                paste.node("blocks-per-tick").getInt(DEFAULT.blocksPerTick()),
                paste.node("chunks-at-once").getInt(DEFAULT.chunksAtOnce()),
                paste.node("paste-air").getBoolean(DEFAULT.pasteAir()),
                paste.node("entities").getBoolean(DEFAULT.entities()),
                paste.node("biomes").getBoolean(DEFAULT.biomes()));
    }

    public PasteOptions options() {
        return PasteOptions.DEFAULT
                .withBlocksPerTick(blocksPerTick)
                .withConcurrency(chunksAtOnce)
                .withPasteAir(pasteAir)
                .withEntities(entities)
                .withBiomes(biomes);
    }
}
