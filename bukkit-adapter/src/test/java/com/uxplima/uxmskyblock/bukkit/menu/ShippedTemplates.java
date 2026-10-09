package com.uxplima.uxmskyblock.bukkit.menu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.render.ItemRenderer;
import com.uxplima.uxmlib.menu.runtime.MenuActionContext;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmlib.menu.spec.MenuItemSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpec;
import com.uxplima.uxmlib.menu.spec.MenuSpecLoader;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;

/**
 * A menu file as it ships, read the way the engine reads it: the template a list stamps, drawn for one
 * row, and a verb run the way a click runs it.
 */
public final class ShippedTemplates {

    private ShippedTemplates() {}

    /** The shipped {@code menus/<file>}, parsed. */
    public static MenuSpec spec(String file) {
        try (InputStream in = ShippedTemplates.class.getClassLoader().getResourceAsStream("menus/" + file)) {
            Objects.requireNonNull(in, "menus/" + file + " is not shipped");
            return new MenuSpecLoader().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    /** The engine a server builds, with the shipped {@code files} in its menus folder and read. */
    public static SkyblockMenuEngine engineWith(java.nio.file.Path dataDir, String... files) {
        try {
            java.nio.file.Path menus = java.nio.file.Files.createDirectories(dataDir.resolve("menus"));
            for (String file : files) {
                try (InputStream in = ShippedTemplates.class.getClassLoader().getResourceAsStream("menus/" + file)) {
                    java.nio.file.Files.copy(
                            Objects.requireNonNull(in, "menus/" + file + " is not shipped"),
                            menus.resolve(file),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException unwritable) {
            throw new IllegalStateException(unwritable);
        }
        SkyblockMenuEngine engine = new SkyblockMenuEngine(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        engine.loadSpecs();
        return engine;
    }

    /** The template the list of {@code item} in {@code file} stamps once per row. */
    public static MenuItemSpec template(String file, String item) {
        MenuItemSpec list = Objects.requireNonNull(spec(file).items().get(item), item + " in " + file);
        return list.list().orElseThrow().template();
    }

    /** The renderer the server draws with, over the engine's placeholders. */
    public static ItemRenderer renderer(SkyblockMenuEngine engine, Messages messages) {
        return new ItemRenderer(
                new CatalogueMenuWords(messages),
                messages.styler()::theme,
                engine.bindings().placeholders());
    }

    /** The tooltip of {@code item} drawn for {@code ctx}, one line each, as the player reads it. */
    public static String lore(ItemRenderer renderer, MenuItemSpec item, MenuContext ctx) {
        return renderer.lore(item, ctx).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .collect(Collectors.joining("\n"));
    }

    /** Runs {@code verb} with {@code arg} the way a click on a tile drawn for {@code ctx} runs it. */
    public static void click(
            SkyblockMenuEngine engine, String verb, MenuContext ctx, Player player, ClickKind kind, String arg) {
        engine.bindings()
                .actions()
                .get(verb)
                .orElseThrow(() -> new AssertionError(verb + " is not registered"))
                .accept(new MenuActionContext(ctx, player, kind, Map.of("value", arg)));
    }

    /** Every verb {@code item}'s gestures name in {@code file}, so a test can see the file names what the code registers. */
    public static Map<ClickKind, List<String>> verbs(MenuItemSpec item) {
        return item.click().actions().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(ref -> ref.id()).toList()));
    }
}
