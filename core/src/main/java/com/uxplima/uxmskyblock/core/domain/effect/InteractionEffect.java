package com.uxplima.uxmskyblock.core.domain.effect;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One thing that happens when an interaction happens.
 *
 * <p>An interaction fires as many of these as the operator wrote and in the order they wrote them.
 * A sound and a particle used to be two lines of Java each, so a server that wanted a different
 * note, or no note, or a title as well, had no way to say so.
 *
 * <p>The shape is one word, a colon, and whatever that word takes:
 *
 * <pre>
 *   message:limits.reached
 *   title:mission.done
 *   subtitle:mission.done_detail
 *   actionbar:limits.reached
 *   bossbar:mission.done PURPLE 5
 *   sound:UI_TOAST_CHALLENGE_COMPLETE 1.0 1.0
 *   particle:CRIT 20 0.8 0.2 0.8 0.1
 * </pre>
 *
 * <p>Every word a player reads is a catalogue key, never the sentence itself, so an operator who
 * writes one writes it in as many languages as they have files.
 */
public record InteractionEffect(Kind kind, String arguments) {

    /** What an effect is. Each takes its own arguments after the colon. */
    public enum Kind {
        /** A line of chat, named by its catalogue key. */
        MESSAGE,
        /** The big words in the middle of the screen, named by their catalogue key. */
        TITLE,
        /** The smaller words under them. */
        SUBTITLE,
        /** The line above the hotbar. */
        ACTION_BAR,
        /** A bar across the top: a catalogue key, a colour and how many seconds it stays. */
        BOSS_BAR,
        /** A sound: its name, then volume and pitch. */
        SOUND,
        /** A particle: its name, how many, then the spread and the speed. */
        PARTICLE
    }

    public InteractionEffect {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        arguments = arguments.strip();
    }

    /**
     * Reads one written line, or nothing when the word before the colon is not one of ours.
     *
     * <p>A line an operator mistyped is skipped rather than fatal. An island that cannot be entered
     * because one effect line has a typo in it is worse than an island that enters quietly.
     */
    public static Optional<InteractionEffect> parse(String line) {
        Objects.requireNonNull(line, "line must not be null");
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        String word = line.substring(0, colon).strip().toUpperCase(Locale.ROOT).replace('-', '_');
        String rest = line.substring(colon + 1).strip();
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        return switch (word) {
            case "MESSAGE" -> Optional.of(new InteractionEffect(Kind.MESSAGE, rest));
            case "TITLE" -> Optional.of(new InteractionEffect(Kind.TITLE, rest));
            case "SUBTITLE" -> Optional.of(new InteractionEffect(Kind.SUBTITLE, rest));
            case "ACTIONBAR", "ACTION_BAR" -> Optional.of(new InteractionEffect(Kind.ACTION_BAR, rest));
            case "BOSSBAR", "BOSS_BAR" -> Optional.of(new InteractionEffect(Kind.BOSS_BAR, rest));
            case "SOUND" -> Optional.of(new InteractionEffect(Kind.SOUND, rest));
            case "PARTICLE" -> Optional.of(new InteractionEffect(Kind.PARTICLE, rest));
            default -> Optional.empty();
        };
    }

    /** The arguments split on spaces, which is how every kind above reads its own. */
    public String[] words() {
        return arguments.isEmpty() ? new String[0] : arguments.split("\\s+");
    }

    /** The first word, which is the catalogue key or the sound or particle name. */
    public String name() {
        String[] words = words();
        return words.length == 0 ? "" : words[0];
    }

    /** The {@code index}th number after the name, or {@code fallback} when it is not there. */
    public double number(int index, double fallback) {
        String[] words = words();
        int at = index + 1;
        if (at >= words.length) {
            return fallback;
        }
        try {
            return Double.parseDouble(words[at]);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
