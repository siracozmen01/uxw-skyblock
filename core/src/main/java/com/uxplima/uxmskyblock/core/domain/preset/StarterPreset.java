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
 * {@code dimensions} says the same for the Nether, the End and any other dimension, by dimension id.
 * {@code world} is the world its islands are made in, blank for the server's own island world: a mode
 * played on generated land names a world the server makes the usual way.
 */
public record StarterPreset(
        String id,
        String displayName,
        String description,
        String schematicPath,
        IslandBiome defaultBiome,
        GameModeType mode,
        List<String> start,
        StartTemplateBundle dimensions,
        String world) {

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
        Objects.requireNonNull(dimensions, "dimensions must not be null");
        Objects.requireNonNull(world, "world must not be null");
        world = world.strip();
        start = List.copyOf(start);
        if (start.isEmpty()) {
            throw new IllegalArgumentException("The preset " + id + " builds nothing: its start names no action");
        }
    }

    /** A preset made in the server's own island world. */
    public StarterPreset(
            String id,
            String displayName,
            String description,
            String schematicPath,
            IslandBiome defaultBiome,
            GameModeType mode,
            List<String> start,
            StartTemplateBundle dimensions) {
        this(id, displayName, description, schematicPath, defaultBiome, mode, start, dimensions, "");
    }

    /**
     * The world this preset's islands are made in: the one it names, or {@code islandWorld}, the
     * server's own island world, when it names none.
     */
    public String worldOr(String islandWorld) {
        return world.isEmpty() ? islandWorld : world;
    }

    /** A preset that starts every other dimension the way the plugin ships. */
    public StarterPreset(
            String id,
            String displayName,
            String description,
            String schematicPath,
            IslandBiome defaultBiome,
            GameModeType mode,
            List<String> start) {
        this(id, displayName, description, schematicPath, defaultBiome, mode, start, StartTemplateBundle.shipped());
    }

    /** A skyblock preset that lays the starter platform. */
    public StarterPreset(
            String id, String displayName, String description, String schematicPath, IslandBiome defaultBiome) {
        this(id, displayName, description, schematicPath, defaultBiome, GameModeType.SKYBLOCK, List.of(PLATFORM));
    }
}
