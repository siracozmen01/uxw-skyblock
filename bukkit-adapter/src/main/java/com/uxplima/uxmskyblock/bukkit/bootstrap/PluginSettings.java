package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

import com.uxplima.uxmlib.config.HoconConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * Brings an operator's file up to the release that is running, and the one class that touches
 * {@link HoconConfig}.
 *
 * <p>A file written on the first run and never read again gives every key a later release adds to a
 * new server and to no existing one. The boot hands each shipped file here right after it writes the
 * defaults, and what the release added and the operator's file lacks is written into it. No value
 * anybody wrote is changed, and the file as it was is kept beside it as {@code <name>.bak}.
 *
 * <p>A key is added only when it is new to this release. The file each release shipped is kept under
 * {@code .defaults/}, and a key that file already held and the operator's does not was deleted on
 * purpose: a mission, a preset or an upgrade tier taken out stays out. A server that has no such copy
 * yet, the first boot of a release that brings this in, gets every key it lacks, once.
 */
public final class PluginSettings {

    /** The folder, beside the operator's files, that holds what each file shipped as last time. */
    public static final String BASELINE_DIRECTORY = ".defaults";

    private PluginSettings() {}

    /**
     * Adds to {@code file} what {@code resource} ships and the operator's copy lacks, and returns
     * whether anything was written.
     *
     * @param dataDir the plugin's folder: {@code .defaults/} lives under it
     * @param file the operator's file, left alone when it is not there
     * @param resource the path of the shipped file inside the jar
     */
    public static boolean bringUpToDate(Path dataDir, Path file, String resource, ClassLoader resources) {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(resources, "resources");
        byte[] shippedBytes = readResource(resource, resources);
        if (shippedBytes == null || !Files.isRegularFile(file)) {
            return false;
        }
        Path baselineFile = dataDir.resolve(BASELINE_DIRECTORY).resolve(resource);
        CommentedConfigurationNode shipped = parse(shippedBytes, resource);
        CommentedConfigurationNode baseline =
                Files.isRegularFile(baselineFile) ? parse(readFile(baselineFile), baselineFile.toString()) : null;

        HoconConfig live = HoconConfig.load(file);
        CommentedConfigurationNode added = CommentedConfigurationNode.root();
        addNewSince(shipped, baseline, live.root(), added);
        boolean wrote = !added.childrenMap().isEmpty() && live.mergeDefaults(added);
        remember(baselineFile, shippedBytes);
        return wrote;
    }

    /**
     * Copies into {@code into} each child of {@code shipped} that the last release did not ship. A child
     * both releases ship is looked into only when the operator still has it: new keys under a section the
     * operator took out would bring back half of it.
     */
    private static void addNewSince(
            ConfigurationNode shipped,
            @Nullable ConfigurationNode baseline,
            ConfigurationNode live,
            CommentedConfigurationNode into) {
        for (Map.Entry<Object, ? extends ConfigurationNode> child :
                shipped.childrenMap().entrySet()) {
            Object key = child.getKey();
            ConfigurationNode was = baseline == null ? null : baseline.node(key);
            if (was == null || was.virtual()) {
                into.node(key).from(child.getValue());
                continue;
            }
            ConfigurationNode liveChild = live.node(key);
            if (child.getValue().isMap() && was.isMap() && liveChild.isMap()) {
                CommentedConfigurationNode nested = CommentedConfigurationNode.root();
                addNewSince(child.getValue(), was, liveChild, nested);
                if (!nested.childrenMap().isEmpty()) {
                    into.node(key).from(nested);
                }
            }
        }
    }

    private static byte @Nullable [] readResource(String resource, ClassLoader resources) {
        try (InputStream in = resources.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException("The shipped " + resource + " could not be read", unreadable);
        }
    }

    private static byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Could not read " + file, unreadable);
        }
    }

    private static CommentedConfigurationNode parse(byte[] bytes, String name) {
        try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return HoconConfigurationLoader.builder()
                    .source(() -> new java.io.BufferedReader(reader))
                    .build()
                    .load();
        } catch (IOException malformed) {
            throw new IllegalStateException("Could not parse " + name, malformed);
        }
    }

    /** Keeps what this release shipped, so the next one knows which keys are new. */
    private static void remember(Path baselineFile, byte[] shipped) {
        try {
            Files.createDirectories(Objects.requireNonNull(baselineFile.getParent()));
            Path temporary = baselineFile.resolveSibling(baselineFile.getFileName() + ".tmp");
            Files.write(temporary, shipped);
            Files.move(temporary, baselineFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unwritable) {
            throw new IllegalStateException("Could not keep the shipped copy at " + baselineFile, unwritable);
        }
    }
}
