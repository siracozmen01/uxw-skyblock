package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.Optional;

/** Where the templates of wrecks and ruins are read from, by their key such as {@code minecraft:shipwreck/with_mast}. */
@FunctionalInterface
public interface WreckTemplates {

    /** The template of that key, or empty when there is none. */
    Optional<WreckTemplate> load(String key);
}
