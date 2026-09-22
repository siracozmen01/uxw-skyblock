package com.uxplima.uxmskyblock.bukkit.bedrock;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmlib.bedrock.BedrockWidget;
import org.jspecify.annotations.Nullable;

/**
 * The Bedrock form sender, found once floodgate has enabled rather than at the moment it was built.
 *
 * <p>The same trap as {@link LateBedrockDetector}: chosen while floodgate was not yet enabled, the
 * library's screen stayed the one that sends nothing.
 */
public final class LateBedrockScreen implements BedrockScreen {

    private final Supplier<BedrockScreen> resolver;
    private volatile @Nullable BedrockScreen found;

    public LateBedrockScreen(Supplier<BedrockScreen> resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    /** The screen for this server, resolved when it is first used after floodgate enables. */
    public static LateBedrockScreen forServer(Server server) {
        Objects.requireNonNull(server, "server must not be null");
        return new LateBedrockScreen(() -> BedrockScreen.forServer(server));
    }

    private BedrockScreen current() {
        BedrockScreen known = found;
        if (known != null) {
            return known;
        }
        BedrockScreen asked = resolver.get();
        if (asked != BedrockScreen.NONE) {
            found = asked;
        }
        return asked;
    }

    @Override
    public void sendSimpleForm(
            Player player, String title, @Nullable String content, List<BedrockButton> buttons, IntConsumer onSelect) {
        current().sendSimpleForm(player, title, content, buttons, onSelect);
    }

    @Override
    public void sendModalForm(
            Player player,
            String title,
            @Nullable String content,
            String button1,
            String button2,
            Runnable onButton1,
            Runnable onButton2) {
        current().sendModalForm(player, title, content, button1, button2, onButton1, onButton2);
    }

    @Override
    public void sendInputForm(
            Player player,
            String title,
            String inputLabel,
            @Nullable String initial,
            Consumer<String> onSubmit,
            Runnable onClose) {
        current().sendInputForm(player, title, inputLabel, initial, onSubmit, onClose);
    }

    @Override
    public void sendCustomForm(
            Player player,
            String title,
            @Nullable String content,
            List<BedrockWidget> widgets,
            Consumer<Map<String, String>> onSubmit,
            Runnable onClose) {
        current().sendCustomForm(player, title, content, widgets, onSubmit, onClose);
    }
}
