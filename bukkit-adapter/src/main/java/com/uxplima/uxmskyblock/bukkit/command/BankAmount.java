package com.uxplima.uxmskyblock.bukkit.command;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An amount of money as a player writes it: {@code 2500}, {@code 2,500}, {@code 2.5k}, {@code 1m} or {@code 3b}.
 *
 * <p>The bank took a whole number and nothing else, so a player asked for an amount by the bank window, who typed
 * {@code 2.5k} or {@code 1,000}, was answered with the server's own parser error. A comma, a space or an underscore
 * between digits groups them. A point is a decimal point and only means something before a suffix: {@code 2.5k} is
 * 2500, and {@code 2.5} is not an amount, because the bank moves whole units.
 */
final class BankAmount {

    private static final Pattern WRITTEN = Pattern.compile("\\$?([0-9]+(?:\\.[0-9]+)?)([kmb]?)");

    private BankAmount() {}

    /** The whole amount {@code text} means, or nothing when it is not a positive whole amount. */
    static OptionalLong parse(String text) {
        String compact = text.strip().toLowerCase(Locale.ROOT).replaceAll("(?<=[0-9])[,_ ](?=[0-9])", "");
        Matcher matcher = WRITTEN.matcher(compact);
        if (!matcher.matches()) {
            return OptionalLong.empty();
        }
        BigDecimal amount = new BigDecimal(matcher.group(1)).multiply(scale(matcher.group(2)));
        try {
            long whole = amount.longValueExact();
            return whole > 0 ? OptionalLong.of(whole) : OptionalLong.empty();
        } catch (ArithmeticException notWholeOrTooLarge) {
            return OptionalLong.empty();
        }
    }

    private static BigDecimal scale(String suffix) {
        return switch (suffix) {
            case "k" -> BigDecimal.valueOf(1_000L);
            case "m" -> BigDecimal.valueOf(1_000_000L);
            case "b" -> BigDecimal.valueOf(1_000_000_000L);
            default -> BigDecimal.ONE;
        };
    }
}
