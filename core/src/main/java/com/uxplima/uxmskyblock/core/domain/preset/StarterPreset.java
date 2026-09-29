package com.uxplima.uxmskyblock.core.domain.preset;

import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;

/**
 * An island a player may start with.
 *
 * <p>{@code mode} is the game mode the island plays, which its game mode instance records.
 * {@code start} names the creation actions that build it, in order: a classic island lays a platform,
 * a OneBlock island sets its one block. Creation runs the list and never asks which mode it is making.
 */
public record StarterPreset(
        String id,
        String displayName,
        String description,
        String schematicPath,
        IslandBiome defaultBiome,
        GameModeType mode,
        List<String> start) {

    /** The action that lays the starter platform, which a preset runs when it names none. */
    public static final String PLATFORM = "uxm:platform";

    public StarterPreset {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(schematicPath, "schematicPath must not be null");
        Objects.requireNonNull(defaultBiome, "defaultBiome must not be null");
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(start, "start must not be null");
        start = List.copyOf(start);
        if (start.isEmpty()) {
            throw new IllegalArgumentException("The preset " + id + " builds nothing: its start names no action");
        }
    }

    /** A skyblock preset that lays the starter platform. */
    public StarterPreset(
            String id, String displayName, String description, String schematicPath, IslandBiome defaultBiome) {
        this(id, displayName, description, schematicPath, defaultBiome, GameModeType.SKYBLOCK, List.of(PLATFORM));
    }
}
