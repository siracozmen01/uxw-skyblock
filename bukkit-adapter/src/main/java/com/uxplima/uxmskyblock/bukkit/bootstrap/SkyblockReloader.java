package com.uxplima.uxmskyblock.bukkit.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import org.jspecify.annotations.Nullable;

/**
 * Reads the files an operator may change while the server is running.
 *
 * <p>The specification draws the line and this holds it: a reload touches the message catalogues
 * and the menu specifications, and nothing else. Core service registrations, Bukkit listeners,
 * connection pools and database tables are never torn down or re-bound. Hot swapping a subsystem is
 * how a plugin leaks classloaders, leaves listeners behind and desynchronises schedulers, and the
 * answer to changing a module is a restart.
 *
 * <p>Nothing here counts languages. Whatever {@code messages/} holds is what is read.
 */
public final class SkyblockReloader {

    /** What one reload did. */
    public record ReloadReport(int catalogues, int menus, List<String> failures) {

        public ReloadReport {
            Objects.requireNonNull(failures, "failures must not be null");
            failures = List.copyOf(failures);
        }

        /** Whether every file the operator has was read without complaint. */
        public boolean clean() {
            return failures.isEmpty();
        }
    }

    private final MessageProvider messageProvider;
    private final Path dataDir;
    private final @Nullable SkyblockMenuEngine menuEngine;

    public SkyblockReloader(MessageProvider messageProvider, Path dataDir, @Nullable SkyblockMenuEngine menuEngine) {
        this.messageProvider = Objects.requireNonNull(messageProvider, "messageProvider must not be null");
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir must not be null");
        this.menuEngine = menuEngine;
    }

    /**
     * Reads the catalogues and the menus again.
     *
     * <p>Every file is read on its own. One that will not parse is named in the report and every
     * other file is still read, because a broken menu must not take the rest of a server with it.
     */
    public ReloadReport reload() {
        List<String> failures = new ArrayList<>();
        int catalogues = reloadCatalogues(failures);
        int menus = reloadMenus();
        return new ReloadReport(catalogues, menus, failures);
    }

    /**
     * Reads every {@code messages_<tag>.conf} the operator has, over the bundled defaults.
     *
     * <p>The bundled catalogues are read first so a key the operator's file does not carry still
     * answers, which is the same order the server starts in.
     */
    private int reloadCatalogues(List<String> failures) {
        messageProvider.loadBundledDefaults(SkyblockReloader.class.getClassLoader());

        Path messagesDir = dataDir.resolve("messages");
        if (!Files.isDirectory(messagesDir)) {
            return 0;
        }
        int read = 0;
        try (Stream<Path> files = Files.list(messagesDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.startsWith("messages_") || !name.endsWith(".conf")) {
                    continue;
                }
                String locale = name.substring("messages_".length(), name.length() - ".conf".length());
                try {
                    messageProvider.loadFromFile(locale, file);
                    read++;
                } catch (IOException | RuntimeException e) {
                    failures.add(name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            failures.add("messages: " + e.getMessage());
        }
        return read;
    }

    /** Reads the menu files again. The engine reports and skips one it cannot parse. */
    private int reloadMenus() {
        if (menuEngine == null) {
            return 0;
        }
        menuEngine.loadSpecs();
        return menuEngine.loadedSpecs().size();
    }
}
