package com.uxplima.uxmskyblock.bukkit.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.uxplima.uxmlib.text.style.Theme;
import com.uxplima.uxmlib.text.style.ThemeFiles;
import org.spongepowered.configurate.ConfigurateException;

/**
 * Where the look of the server is read from: the file every plugin of ours shares, with this plugin's
 * own file on top of it.
 *
 * <p>A server runs a suite of our plugins and expects one look, so the palette lives once, beside the
 * plugins that read it. A {@code theme.conf} in this plugin's own folder still wins key by key, for the
 * server that wants this one plugin to read differently.
 */
public final class ThemeSource {

    private static final Logger LOGGER = Logger.getLogger(ThemeSource.class.getName());

    /** The folder the suite shares its theme in, beside the plugins that read it. */
    static final String SHARED_FOLDER = "uxmTheme";

    private static final String FILE = "theme.conf";

    private ThemeSource() {}

    /**
     * The theme the server reads now. A file that cannot be read is reported and the shipped palette is
     * used, because a broken colour must not stop a server.
     */
    public static Theme load(Path dataDir) {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        try {
            return ThemeFiles.load(ThemeFiles.shared(dataDir, SHARED_FOLDER), ThemeFiles.own(dataDir));
        } catch (ConfigurateException | IllegalArgumentException unreadable) {
            LOGGER.log(Level.WARNING, unreadable, () -> "Cannot read theme.conf. The shipped palette is used.");
            return Theme.defaults();
        }
    }

    /**
     * Writes the shipped palette where every plugin of ours reads it, on the first run.
     *
     * <p>It goes beside this plugin's folder rather than inside it, so the second plugin a server installs
     * finds the file the first one wrote and the whole suite reads one look. An existing file is never
     * touched, whoever wrote it.
     */
    public static void saveShared(Path dataDir, ClassLoader resources) {
        Objects.requireNonNull(dataDir, "dataDir must not be null");
        Objects.requireNonNull(resources, "resources must not be null");
        Path shared = ThemeFiles.shared(dataDir, SHARED_FOLDER);
        if (Files.isRegularFile(shared)) {
            return;
        }
        try (InputStream shipped = resources.getResourceAsStream(FILE)) {
            if (shipped == null) {
                return;
            }
            Path folder = shared.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.copy(shipped, shared);
        } catch (IOException unwritable) {
            LOGGER.log(
                    Level.WARNING,
                    unwritable,
                    () -> "Cannot write the theme to " + shared + ". The shipped palette is used.");
        }
    }
}
