package com.uxplima.uxmskyblock.bukkit.schematic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bukkit.Location;
import org.bukkit.World;

import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmlib.schematic.format.SpongeSchematicReader;
import com.uxplima.uxmlib.schematic.format.SpongeSchematicWriter;
import com.uxplima.uxmlib.schematic.paper.CaptureOptions;
import com.uxplima.uxmlib.schematic.paper.PasteOptions;
import com.uxplima.uxmlib.schematic.paper.PasteReport;
import com.uxplima.uxmlib.schematic.paper.Rotation;
import com.uxplima.uxmlib.schematic.paper.SchematicCapture;
import com.uxplima.uxmlib.schematic.paper.SchematicPaster;

/**
 * The structure files of a server, under the plugin's data folder, read with nothing else installed.
 *
 * <p>A preset names its file by a path under the data folder, {@code schematics/classic.schem}; the admin
 * command names one by a bare name, {@code classic}, which is the same file. A path that leaves the data
 * folder is refused, so a preset written as {@code ../../server.properties} reads nothing.
 *
 * <p>A file is read once and kept while it is unchanged on disk, so every island started from a preset does
 * not read it again. Reading and writing happen on {@code io}, off the server's threads.
 */
public final class IslandSchematics {

    /** The folder the admin command saves into and lists, under the data folder. */
    public static final String FOLDER = "schematics";

    /** The ending every file this plugin writes carries, and every name the admin command takes is given. */
    public static final String EXTENSION = ".schem";

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,48}");

    private final Path dataFolder;
    private final Path folder;
    private final Executor io;
    private final Paste paster;
    private final Capture capture;
    private final PasteOptions options;
    private final SpongeSchematicReader reader = SpongeSchematicReader.withDefaults();
    private final Map<Path, Read> read = new ConcurrentHashMap<>();

    /** A file as it was read, and how it stood on disk then. */
    private record Read(long modified, long size, Schematic schematic) {}

    /** Puts a schematic down; {@link SchematicPaster#paste} on a server. */
    @FunctionalInterface
    public interface Paste {
        CompletableFuture<PasteReport> paste(Schematic schematic, Location at, PasteOptions options);
    }

    /** Saves a box of a world; {@link SchematicCapture#capture} on a server. */
    @FunctionalInterface
    public interface Capture {
        CompletableFuture<Schematic> capture(
                World world, Vec3i corner, Vec3i otherCorner, Vec3i origin, CaptureOptions options);
    }

    public IslandSchematics(Path dataFolder, Executor io, Paste paster, Capture capture, PasteOptions options) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder must not be null")
                .toAbsolutePath()
                .normalize();
        this.folder = this.dataFolder.resolve(FOLDER);
        this.io = Objects.requireNonNull(io, "io must not be null");
        this.paster = Objects.requireNonNull(paster, "paster must not be null");
        this.capture = Objects.requireNonNull(capture, "capture must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
    }

    /** Whether {@code name} is one the admin command takes: lower case letters, digits, {@code _} and {@code -}. */
    public static boolean isName(String name) {
        return NAME.matcher(name).matches();
    }

    /** The data folder path of the file the admin command calls {@code name}. */
    public static String pathOf(String name) {
        return FOLDER + "/" + name + EXTENSION;
    }

    /**
     * The schematic at {@code path} under the data folder, read off the server's threads, or nothing when no
     * file is there. A file that is there and cannot be read fails the future, saying why.
     */
    public CompletableFuture<Optional<Schematic>> read(String path) {
        return CompletableFuture.supplyAsync(() -> readNow(path), io);
    }

    /** Puts {@code schematic} down with the point it was saved around on {@code at}. */
    public CompletableFuture<PasteReport> paste(Schematic schematic, Location at, Rotation rotation) {
        return paster.paste(schematic, at, options.withRotation(rotation));
    }

    /**
     * Saves the box between two corners as {@code name}, around {@code origin}, replacing a file of that name.
     * Answers the schematic saved.
     */
    public CompletableFuture<Schematic> save(
            String name, World world, Vec3i corner, Vec3i otherCorner, Vec3i origin, CaptureOptions captureOptions) {
        requireName(name);
        return capture.capture(world, corner, otherCorner, origin, captureOptions)
                .thenApplyAsync(
                        saved -> {
                            write(pathOf(name), saved);
                            return saved;
                        },
                        io);
    }

    /** Writes {@code schematic} to {@code path} under the data folder unless a file is there already. */
    public CompletableFuture<Boolean> writeIfMissing(String path, Schematic schematic) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (Files.exists(resolve(path))) {
                        return false;
                    }
                    write(path, schematic);
                    return true;
                },
                io);
    }

    /** The names of the files in {@code schematics/}, in order. */
    public CompletableFuture<List<String>> list() {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (!Files.isDirectory(folder)) {
                        return List.<String>of();
                    }
                    List<String> names = new ArrayList<>();
                    try (Stream<Path> files = Files.list(folder)) {
                        files.map(file -> file.getFileName().toString())
                                .filter(file -> file.endsWith(EXTENSION))
                                .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                                .sorted()
                                .forEach(names::add);
                    } catch (IOException e) {
                        throw new UncheckedIOException("could not list " + folder, e);
                    }
                    return List.copyOf(names);
                },
                io);
    }

    Optional<Schematic> readNow(String path) {
        Path file = resolve(path);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class);
        } catch (NoSuchFileException absent) {
            read.remove(file);
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not look at " + path, e);
        }
        long modified = attributes.lastModifiedTime().toMillis();
        Read known = read.get(file);
        if (known != null && known.modified() == modified && known.size() == attributes.size()) {
            return Optional.of(known.schematic());
        }
        try (InputStream in = Files.newInputStream(file)) {
            Schematic schematic = reader.read(in);
            read.put(file, new Read(modified, attributes.size(), schematic));
            return Optional.of(schematic);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path + ": " + e.getMessage(), e);
        }
    }

    private void write(String path, Schematic schematic) {
        Path file = resolve(path);
        try {
            Files.createDirectories(file.getParent());
            Path written = Files.createTempFile(file.getParent(), ".writing-", EXTENSION);
            try {
                try (OutputStream out = Files.newOutputStream(written)) {
                    SpongeSchematicWriter.write(schematic, out);
                }
                try {
                    Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException notOnThisDisk) {
                    Files.move(written, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(written);
            }
            read.remove(file);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + path, e);
        }
    }

    /** {@code path} under the data folder, refused when it leads out of it. */
    private Path resolve(String path) {
        Objects.requireNonNull(path, "path must not be null");
        Path file = dataFolder.resolve(path).normalize();
        if (!file.startsWith(dataFolder) || file.equals(dataFolder)) {
            throw new IllegalArgumentException("'" + path + "' is not a file under the plugin's folder");
        }
        return file;
    }

    private static void requireName(String name) {
        if (!isName(name)) {
            throw new IllegalArgumentException("'" + name + "' is not a schematic name");
        }
    }

    /** {@code text} as a name, lower case, or nothing when it is not one. */
    public static Optional<String> nameOf(String text) {
        String name = text.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(EXTENSION)) {
            name = name.substring(0, name.length() - EXTENSION.length());
        }
        return isName(name) ? Optional.of(name) : Optional.empty();
    }
}
