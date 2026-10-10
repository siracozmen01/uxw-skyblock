package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every line of {@code /is help} describes the command it names as the command really is.
 *
 * <p>The help said {@code /is delete <target>} for a word that takes no target and erases your own
 * island, and {@code /is allychat <message>} for a word that took no message, so a player who typed
 * what the help told them read an argument error. This reads each usage the catalogue gives and asks
 * the tree the plugin registers whether the branch has that shape.
 */
class EveryHelpLineMatchesItsCommandTest {

    @Test
    @DisplayName("A help line names a value only where the command takes one, and every word it offers exists")
    void everyUsageFitsItsBranch() {
        MessageProvider catalogue =
                com.uxplima.uxmskyblock.bukkit.i18n.Messages.bundled().provider();
        CommandNode<CommandSourceStack> root =
                EveryMenuCommandParsesTest.dispatcher().getRoot().getChild("island");
        List<String> wrong = new ArrayList<>();
        int read = 0;
        for (String language : List.of("en", "tr")) {
            for (CommandNode<CommandSourceStack> branch : root.getChildren()) {
                String key = "help.commands." + branch.getName() + ".usage";
                if (!catalogue.getKeys(language).contains(key)) {
                    continue;
                }
                read++;
                String usage = catalogue
                        .getRaw(key, language)
                        .replace("<plain>", "")
                        .replace("</plain>", "")
                        .replace("\\", "")
                        .strip();
                String where = language + ": /is " + branch.getName();
                if (usage.isEmpty()) {
                    if (branch.getCommand() == null) {
                        wrong.add(where + " is listed with nothing after it and needs more");
                    }
                    continue;
                }
                walk(branch, usage, where, wrong);
            }
        }

        assertThat(read).describedAs("help lines read").isGreaterThan(80);
        assertThat(wrong).isEmpty();
    }

    /**
     * Follows {@code usage} down from {@code node}, one word at a time. A bare word is a word the
     * command takes. A word in angle brackets, or alone in square ones, is either such a word or a
     * value, and a value needs an argument where it stands. A choice in square brackets offers words,
     * each of which the command takes, unless a value stands there and takes any of them.
     */
    private static void walk(CommandNode<CommandSourceStack> node, String usage, String where, List<String> wrong) {
        CommandNode<CommandSourceStack> at = node;
        for (String token : tokens(usage)) {
            boolean optional = token.startsWith("[");
            String inner = optional || token.startsWith("<") ? token.substring(1, token.length() - 1) : token;
            if (!token.equals(inner) && inner.contains("|")) {
                boolean free = hasArgument(at);
                for (String option : Pattern.compile("\\|").splitAsStream(inner).toList()) {
                    if (option.equals("...") || free) {
                        continue;
                    }
                    walk(at, option, where, wrong);
                }
                return;
            }
            if (inner.contains(" ")) {
                walk(at, inner, where, wrong);
                return;
            }
            CommandNode<CommandSourceStack> literal = at.getChild(inner);
            if (literal instanceof LiteralCommandNode) {
                at = literal;
            } else if (token.equals(inner)) {
                wrong.add(where + " offers " + inner + ", which it does not take");
                return;
            } else if (hasArgument(at)) {
                at = at.getChildren().stream()
                        .filter(ArgumentCommandNode.class::isInstance)
                        .findFirst()
                        .orElseThrow();
            } else {
                wrong.add(where + " " + usage + " names " + token + ", which it does not take");
                return;
            }
        }
    }

    private static boolean hasArgument(CommandNode<CommandSourceStack> node) {
        return node.getChildren().stream().anyMatch(ArgumentCommandNode.class::isInstance);
    }

    /** The words of a usage, a bracketed group being one word however many spaces it holds. */
    private static List<String> tokens(String usage) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < usage.length(); i++) {
            char c = usage.charAt(i);
            if (c == '[' || c == '<') {
                depth++;
            } else if (c == ']' || c == '>') {
                depth--;
            }
            if (c == ' ' && depth == 0) {
                if (!word.isEmpty()) {
                    tokens.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (!word.isEmpty()) {
            tokens.add(word.toString());
        }
        return tokens;
    }
}
