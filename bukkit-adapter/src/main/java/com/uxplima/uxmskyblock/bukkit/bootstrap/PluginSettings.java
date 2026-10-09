package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
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
 *
 * <p>The same copy says which values the operator never touched. A value their file still holds exactly
 * as the last release shipped it was never theirs, so when this release ships it differently it takes
 * the new one, and when this release ships it no more it goes. A file nobody edited at all is written
 * out as this release ships it, comments and order included. A value the operator changed is kept.
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
        byte[] baselineBytes = Files.isRegularFile(baselineFile) ? readFile(baselineFile) : null;
        if (baselineBytes != null && Arrays.equals(readFile(file), baselineBytes)) {
            // Nobody edited the file: it becomes what this release ships, comments and order included.
            if (Arrays.equals(baselineBytes, shippedBytes)) {
                return false;
            }
            keepAsItWas(file);
            write(file, shippedBytes);
            remember(baselineFile, shippedBytes);
            return true;
        }
        CommentedConfigurationNode shipped = parse(shippedBytes, resource);
        CommentedConfigurationNode baseline =
                baselineBytes == null ? null : parse(baselineBytes, baselineFile.toString());

        HoconConfig live = HoconConfig.load(file);
        boolean upgraded = baseline != null && upgradeUntouched(shipped, baseline, live.root());
        if (upgraded) {
            keepAsItWas(file);
        }
        // The whole shipped file goes to the merge, less what the operator took out. The merge adds only
        // what the file lacks, and it also names a key of the file that looks like a misspelling of a
        // shipped one: handed the new keys alone, it took every other key the file has for a misspelling.
        boolean merged = live.mergeDefaults(kept(shipped, baseline, live.root()));
        if (upgraded && !merged) {
            live.save();
        }
        remember(baselineFile, shippedBytes);
        return upgraded || merged;
    }

    /**
     * Gives every value of {@code live} that still reads as {@code baseline} shipped it the value
     * {@code shipped} has now, and takes out one {@code shipped} no longer has. Returns whether any changed.
     */
    private static boolean upgradeUntouched(
            ConfigurationNode shipped, ConfigurationNode baseline, ConfigurationNode live) {
        boolean changed = false;
        for (Map.Entry<Object, ? extends ConfigurationNode> child :
                baseline.childrenMap().entrySet()) {
            Object key = child.getKey();
            ConfigurationNode was = child.getValue();
            ConfigurationNode now = shipped.node(key);
            ConfigurationNode theirs = live.node(key);
            if (theirs.virtual()) {
                continue;
            }
            if (was.isMap() && theirs.isMap() && now.isMap()) {
                changed |= upgradeUntouched(now, was, theirs);
            } else if (Objects.equals(theirs.raw(), was.raw()) && !Objects.equals(now.raw(), was.raw())) {
                if (now.virtual()) {
                    live.removeChild(key);
                } else {
                    theirs.from(now);
                }
                changed = true;
            }
        }
        return changed;
    }

    /** Copies {@code file} beside itself as {@code .bak}, before it is written again. */
    private static void keepAsItWas(Path file) {
        try {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unwritable) {
            throw new IllegalStateException("Could not keep a copy of " + file, unwritable);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unwritable) {
            throw new IllegalStateException("Could not write " + file, unwritable);
        }
    }

    /**
     * {@code shipped} without what the operator deleted: a key the last release shipped and their file no
     * longer has was taken out on purpose, so a mission, a preset or an upgrade tier taken out stays out.
     * A section both hold is looked into for the same reason.
     */
    private static CommentedConfigurationNode kept(
            ConfigurationNode shipped, @Nullable ConfigurationNode baseline, ConfigurationNode live) {
        CommentedConfigurationNode kept = CommentedConfigurationNode.root();
        for (Map.Entry<Object, ? extends ConfigurationNode> child :
                shipped.childrenMap().entrySet()) {
            Object key = child.getKey();
            ConfigurationNode was = baseline == null ? null : baseline.node(key);
            ConfigurationNode liveChild = live.node(key);
            boolean shippedBefore = was != null && !was.virtual();
            if (shippedBefore && liveChild.virtual()) {
                continue;
            }
            if (was != null && shippedBefore && child.getValue().isMap() && was.isMap() && liveChild.isMap()) {
                kept.node(key).from(kept(child.getValue(), was, liveChild));
            } else {
                kept.node(key).from(child.getValue());
            }
        }
        return kept;
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
