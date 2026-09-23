package com.uxplima.uxmskyblock.bukkit.menu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.uxplima.uxmlib.common.Log;
import com.uxplima.uxmlib.gui.GuiText;
import com.uxplima.uxmlib.gui.anvil.AnvilInput;
import com.uxplima.uxmlib.gui.input.TextInputInstaller;
import com.uxplima.uxmlib.gui.style.MenuSounds;
import com.uxplima.uxmlib.menu.MenuBasics;
import com.uxplima.uxmlib.menu.Menus;
import com.uxplima.uxmlib.menu.binding.MenuBindings;
import com.uxplima.uxmlib.menu.binding.PlaceholderRegistry;
import com.uxplima.uxmlib.menu.render.ItemRenderer;
import com.uxplima.uxmlib.menu.render.MenuRenderer;
import com.uxplima.uxmlib.menu.runtime.MenuActionContext;
import com.uxplima.uxmlib.menu.runtime.MenuListener;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import com.uxplima.uxmlib.scheduler.PaperScheduler;
import com.uxplima.uxmlib.text.style.Theme;
import com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockDetector;
import com.uxplima.uxmskyblock.bukkit.bedrock.LateBedrockScreen;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * The menu engine, reading this server's own menu files.
 *
 * <p>Three menu files shipped with the plugin from the beginning and nothing ever read one. An
 * operator who opened {@code menus/island-main.conf}, moved a slot and restarted saw no change,
 * because every slot, material, title and lore was decided in Java. That is the opposite of what the
 * files and the documentation both promise.
 *
 * <p>This holds the engine and the vocabulary of verbs a menu file may name. The file says what to
 * run; this says what each verb means. Nothing here decides a colour, a word or a layout.
 */
public final class SkyblockMenuEngine implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SkyblockMenuEngine.class.getName());

    private final MenuBindings bindings = new MenuBindings();
    private final Menus menus;
    private final MenuListener listener;
    private final Path menusDir;
    private final AnvilInput anvilInput;
    private final TextInputInstaller.Installed textInput;
    private final List<String> loaded = new ArrayList<>();

    /**
     * The live values each viewer's menus were last opened with.
     *
     * <p>A menu file says {@code open:island-bank} and the engine opens it on the click thread,
     * where reading a balance from the database is exactly what must not happen. The values were
     * gathered moments earlier, when the player opened the panel these menus hang off, so the
     * remembered set is what the sub menu shows. A balance a few seconds old on a page the player
     * navigated to is the right trade against a query under their cursor.
     */
    private final Map<UUID, Map<String, String>> lastValues = new ConcurrentHashMap<>();

    public SkyblockMenuEngine(Plugin plugin, Messages messages, Path dataDir, @Nullable ConfigurationNode themeNode) {
        Objects.requireNonNull(plugin, "plugin must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(dataDir, "dataDir must not be null");

        this.menusDir = dataDir.resolve("menus");
        Theme theme = themeNode == null ? Theme.defaults() : Theme.from(themeNode);
        GuiText words = new CatalogueMenuWords(messages);
        ItemRenderer itemRenderer = new ItemRenderer(words, () -> theme, bindings.placeholders());
        MenuRenderer renderer = new MenuRenderer(itemRenderer, bindings.conditions(), bindings.contents());
        PaperScheduler scheduler = new PaperScheduler(plugin);

        this.menus = new Menus(
                renderer,
                scheduler,
                bindings.lists(),
                null,
                bindings.actions(),
                bindings.conditions(),
                null,
                LateBedrockDetector.forServer(Bukkit.getServer()),
                LateBedrockScreen.forServer(Bukkit.getServer()),
                bindings.pagedLists());
        // The text prompt behind an input: step. Without it a menu file can ask for a warp name and
        // the engine has nowhere to ask it, so the step cancels and the button does nothing. It is
        // also how a Bedrock player gets asked: the seam sends them a native Cumulus form instead of
        // an anvil, which is what "Bedrock is the floor" means for a menu that needs a word typed.
        AnvilInput anvil = new AnvilInput(plugin);
        anvil.install();
        this.anvilInput = anvil;
        this.textInput = TextInputInstaller.install(
                plugin,
                dataDir,
                anvil,
                words,
                scheduler,
                Log.of(LOGGER),
                LateBedrockDetector.forServer(Bukkit.getServer()),
                LateBedrockScreen.forServer(Bukkit.getServer()));

        this.listener = new MenuListener(
                renderer,
                bindings.actions(),
                bindings.conditions(),
                scheduler,
                plugin,
                null,
                null,
                null,
                0L,
                System::currentTimeMillis,
                bindings.pagedLists(),
                this.textInput.textInput()::promptResolved,
                bindings.contents(),
                MenuSounds.defaults(),
                refs -> false);

        // close, open, command, message and sound mean the same in every plugin, so they come from
        // the library. Anything a skyblock menu can do that a generic menu cannot is registered by
        // the feature that owns it, through bindings().
        // Four of the five: close, command, message and sound. Not the library's open, because that
        // one carries no values and a sub menu opened without them shows blanks where a balance
        // should be. The registry refuses a second registration under the same name, which is right:
        // a verb that quietly changed meaning depending on wiring order would be worse.
        MenuBasics.register(bindings);
        answerArguments(bindings.placeholders());

        bindings.action("open", ctx -> {
            String target = ctx.arg().strip();
            menus.open(ctx.player(), target, null, 0, valuesFor(ctx.player().getUniqueId()));
        });
    }

    /**
     * Answers {@code argument_<name>} from the values the menu was opened with, for a catalogue line.
     *
     * <p>A menu file's own {@code %argument_<name>%} is filled by the engine. A catalogue line names
     * its values as {@code <argument_<name>>} and asks for them by name, and the engine answers a name
     * only through this registry, so without this every such line printed its tag.
     */
    static void answerArguments(PlaceholderRegistry placeholders) {
        placeholders.fallback(
                id -> id.startsWith(ARGUMENT),
                (id, ctx) -> ctx.arguments().getOrDefault(id.substring(ARGUMENT.length()), ""));
    }

    private static final String ARGUMENT = "argument_";

    /** What the viewer's menus were last opened with, or nothing when they have opened none. */
    private Map<String, String> valuesFor(UUID viewer) {
        return lastValues.getOrDefault(viewer, Map.of());
    }

    /** Forgets a viewer's values, for a quit or a profile switch. */
    public void forget(UUID viewer) {
        lastValues.remove(Objects.requireNonNull(viewer, "viewer must not be null"));
    }

    /**
     * Reads every {@code menus/*.conf} the operator has, registering each under its file name.
     *
     * <p>A file that will not parse is reported and skipped. One broken menu must not stop a server:
     * the operator gets the file name and the reason, and every other menu still opens.
     */
    public void loadSpecs() {
        if (!Files.isDirectory(menusDir)) {
            return;
        }
        MenuSpecLoader loader = new MenuSpecLoader();
        try (Stream<Path> files = Files.list(menusDir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".conf")) {
                    continue;
                }
                String id = name.substring(0, name.length() - ".conf".length()).toLowerCase(Locale.ROOT);
                try {
                    MenuSpec spec = loader.load(file);
                    menus.registerSpec(id, spec);
                    loaded.add(id);
                } catch (RuntimeException e) {
                    LOGGER.log(
                            Level.WARNING,
                            e,
                            () -> "The menu file " + file + " could not be read, so the menu it "
                                    + "describes will not open. Every other menu still works.");
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, e, () -> "The menus folder " + menusDir + " could not be listed.");
        }
    }

    /** Starts listening for clicks. Called once, after every feature has registered its verbs. */
    public void install() {
        listener.install();
    }

    /** Where a feature registers the verbs its own menus name. */
    public MenuBindings bindings() {
        return bindings;
    }

    public Menus menus() {
        return menus;
    }

    /** The ids of the menu files that loaded, which is what an operator sees in a diagnostic. */
    public List<String> loadedSpecs() {
        return List.copyOf(loaded);
    }

    /** Whether a menu of this id is registered, so a caller can fall back when the file is missing. */
    public boolean has(String specId) {
        return menus.registeredSpec(specId).isPresent();
    }

    /**
     * Opens a menu with live values bound as {@code %argument_<name>%} tokens.
     *
     * <p>The values are gathered by the caller, off the main thread where they come from a database,
     * and passed in. That is the seam between what a file says and what a server knows.
     */
    public boolean open(Player viewer, String specId, Map<String, String> values) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(specId, "specId must not be null");
        Objects.requireNonNull(values, "values must not be null");
        if (!has(specId)) {
            return false;
        }
        lastValues.put(viewer.getUniqueId(), Map.copyOf(values));
        menus.open(viewer, specId, null, 0, values);
        return true;
    }

    /** Registers one verb a skyblock menu file may name. */
    public void action(String id, java.util.function.Consumer<MenuActionContext> handler) {
        bindings.action(id, handler);
    }

    public Optional<MenuSpec> spec(String specId) {
        return menus.registeredSpec(specId);
    }

    @Override
    public void close() {
        lastValues.clear();
        textInput.uninstall().run();
        anvilInput.uninstall();
        listener.uninstall();
        menus.shutdown();
    }
}
