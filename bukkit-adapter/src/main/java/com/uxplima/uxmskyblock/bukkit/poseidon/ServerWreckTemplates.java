package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.structure.UsageMode;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

/**
 * The structure templates the server knows: the game's own shipwrecks and ruins, and any a datapack
 * adds. Each is read once and kept.
 */
public final class ServerWreckTemplates implements WreckTemplates {

    private static final Logger LOGGER = Logger.getLogger(ServerWreckTemplates.class.getName());

    private final Map<String, Optional<WreckTemplate>> read = new ConcurrentHashMap<>();

    @Override
    public Optional<WreckTemplate> load(String key) {
        return read.computeIfAbsent(key, ServerWreckTemplates::readFromServer);
    }

    private static Optional<WreckTemplate> readFromServer(String key) {
        NamespacedKey id = NamespacedKey.fromString(key);
        if (id == null) {
            LOGGER.warning(() -> "modules/poseidon.conf names the template '" + key + "', which is no key.");
            return Optional.empty();
        }
        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(id, false);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "The template " + key + " could not be read.");
            return Optional.empty();
        }
        if (structure == null || structure.getPaletteCount() == 0) {
            LOGGER.warning(() -> "The server has no template " + key + ". modules/poseidon.conf names it.");
            return Optional.empty();
        }
        List<WreckTemplate.Piece> pieces = new ArrayList<>();
        for (BlockState state : structure.getPalettes().getFirst().getBlocks()) {
            String marker = state instanceof org.bukkit.block.Structure block && block.getUsageMode() == UsageMode.DATA
                    ? block.getMetadata()
                    : null;
            pieces.add(new WreckTemplate.Piece(state.getX(), state.getY(), state.getZ(), state.getBlockData(), marker));
        }
        BlockVector size = structure.getSize();
        return Optional.of(new WreckTemplate(size.getBlockX(), size.getBlockZ(), pieces));
    }
}
