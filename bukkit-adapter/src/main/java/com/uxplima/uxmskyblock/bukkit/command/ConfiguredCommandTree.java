package com.uxplima.uxmskyblock.bukkit.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import org.jspecify.annotations.Nullable;

/**
 * The island command as the operator named it in {@code commands.conf}.
 *
 * <p>Every word of the command was written in Java, so a server that already had an {@code /island}
 * from another plugin, or one that plays in a language where {@code invite} means nothing, could not
 * change a single one. The root and each of its branches now read a name, aliases and whether they
 * are on at all from the file, under the key the code knows them by, and a branch the file does not
 * mention keeps its own word.
 *
 * <p>A name another branch already answers to is refused and the branch keeps its own: two branches
 * under one word would merge their arguments, and a player would reach whichever the server tried
 * first.
 *
 * @param <S> the command source
 */
final class ConfiguredCommandTree<S> {

    /** The key the root is read under, and the word it answers to when the file says nothing. */
    static final String ROOT = "island";

    private static final Logger LOGGER = Logger.getLogger(ConfiguredCommandTree.class.getName());

    private final @Nullable ConfiguredCommands names;

    ConfiguredCommandTree(@Nullable ConfiguredCommands names) {
        this.names = names;
    }

    /** The root's own entry: its name, its aliases and whether it is registered at all. */
    ConfiguredCommands.Entry root() {
        return entry(ROOT, ROOT, List.of("is"));
    }

    /** The key's entry for a branch of the root, which answers to its own word when the file is silent. */
    ConfiguredCommands.Entry branch(String key) {
        return entry(ConfiguredCommands.branchKey(ROOT, key), key, List.of());
    }

    /**
     * The tree {@code built} describes, under the names the file gives, with every branch the file
     * turns off left out.
     */
    LiteralArgumentBuilder<S> apply(LiteralArgumentBuilder<S> built) {
        Objects.requireNonNull(built, "built");
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.<S>literal(root().name())
                .requires(built.getRequirement())
                .executes(built.getCommand());
        Set<String> taken = new HashSet<>();
        List<CommandNode<S>> kept = new ArrayList<>();
        for (CommandNode<S> child : built.getArguments()) {
            if (child instanceof LiteralCommandNode<S> literal) {
                taken.add(literal.getLiteral());
            }
            kept.add(child);
        }
        Set<String> answered = new HashSet<>();
        for (CommandNode<S> child : kept) {
            if (!(child instanceof LiteralCommandNode<S> literal)) {
                root.then(child);
                continue;
            }
            String key = literal.getLiteral();
            ConfiguredCommands.Entry entry = branch(key);
            if (!entry.enabled()) {
                continue;
            }
            List<String> words = new ArrayList<>();
            words.add(entry.name());
            words.addAll(entry.aliases());
            for (String word : words) {
                boolean someoneElses = !word.equals(key) && taken.contains(word);
                if (someoneElses || !answered.add(word)) {
                    LOGGER.warning(() -> "commands.conf names the " + key + " branch '" + word
                            + "', which another branch already answers to, so it keeps its own word.");
                    if (answered.add(key)) {
                        root.then(copy(literal, key));
                    }
                    continue;
                }
                root.then(copy(literal, word));
            }
        }
        return root;
    }

    /** {@code node} under another word, with everything it runs, requires and holds. */
    private static <S> LiteralArgumentBuilder<S> copy(LiteralCommandNode<S> node, String word) {
        LiteralArgumentBuilder<S> copy = LiteralArgumentBuilder.<S>literal(word)
                .requires(node.getRequirement())
                .executes(node.getCommand());
        if (node.getRedirect() != null) {
            // A redirected node holds no children of its own; it hands the rest of the line on.
            return copy.forward(node.getRedirect(), node.getRedirectModifier(), node.isFork());
        }
        for (CommandNode<S> child : node.getChildren()) {
            copy.then(child);
        }
        return copy;
    }

    private ConfiguredCommands.Entry entry(String key, String word, List<String> aliases) {
        ConfiguredCommands file = this.names;
        if (file == null) {
            return new ConfiguredCommands.Entry(word, aliases, true);
        }
        return file.entryOf(key, word);
    }
}
