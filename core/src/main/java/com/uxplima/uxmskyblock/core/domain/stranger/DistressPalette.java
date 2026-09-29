package com.uxplima.uxmskyblock.core.domain.stranger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the Upside Down distresses the blocks it mirrors: each rule turns one block, or every block a
 * pattern matches, into another. The first rule that matches wins, and a block no rule matches is
 * mirrored as it is.
 *
 * <p>A rule is written {@code FROM:TO}. {@code FROM} is a block name, or a name with one {@code *} in
 * it, such as {@code *_LEAVES}, which matches every block whose name has that start and end.
 */
public final class DistressPalette {

    /** One written rule. */
    public record Rule(String from, String to) {

        public Rule {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            if (from.isBlank() || to.isBlank() || from.indexOf('*') != from.lastIndexOf('*')) {
                throw new IllegalArgumentException("a rule is FROM:TO with at most one * in FROM");
            }
        }

        /** A rule written {@code FROM:TO}, or empty when it is not one. */
        public static Optional<Rule> parse(String written) {
            String[] parts = written.trim().toUpperCase(Locale.ROOT).split(":", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Rule(parts[0].trim(), parts[1].trim()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        boolean matches(String block) {
            int star = from.indexOf('*');
            if (star < 0) {
                return from.equals(block);
            }
            String start = from.substring(0, star);
            String end = from.substring(star + 1);
            return block.length() >= start.length() + end.length() && block.startsWith(start) && block.endsWith(end);
        }
    }

    private final List<Rule> rules;
    private final Map<String, String> answered = new ConcurrentHashMap<>();

    public DistressPalette(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /** A palette of the rules that read, and the lines that did not. */
    public static DistressPalette parse(List<String> written, List<String> unread) {
        List<Rule> rules = new ArrayList<>();
        for (String line : written) {
            Optional<Rule> rule = Rule.parse(line);
            if (rule.isPresent()) {
                rules.add(rule.get());
            } else {
                unread.add(line);
            }
        }
        return new DistressPalette(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    /** The block the Upside Down puts where the overworld has {@code block}, by its upper case name. */
    public String distressed(String block) {
        return answered.computeIfAbsent(block, this::firstMatch);
    }

    private String firstMatch(String block) {
        for (Rule rule : rules) {
            if (rule.matches(block)) {
                return rule.to();
            }
        }
        return block;
    }
}
