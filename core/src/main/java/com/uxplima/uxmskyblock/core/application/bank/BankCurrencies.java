package com.uxplima.uxmskyblock.core.application.bank;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The names a currency has on its way between a wallet and the island bank.
 *
 * <p>The island's own money is the bank's primary column, kept in minor units, and a saga that moves it is
 * recorded under {@link #PRIMARY_MONEY}, as every saga written before the bank held anything else was. Every other
 * currency is one the operator listed in {@code modules/bank.conf}: it is kept in whole units under its own id,
 * and a saga that moves it is recorded under that id.
 */
public final class BankCurrencies {

    /** What a saga that moves the island's own money is recorded under. */
    public static final String PRIMARY_MONEY = "VAULT";

    /** The bank column the island's own money is kept in, and its scale. */
    public static final String PRIMARY_COLUMN = "PRIMARY";

    public static final int PRIMARY_SCALE = 2;

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** Names the bank keeps for itself, which no operator currency may take. */
    private static final Set<String> RESERVED = Set.of("primary", "crystals", "exp", "vault");

    private BankCurrencies() {}

    /** Whether {@code id} names an operator currency the bank can hold: lower case, short, and not its own. */
    public static boolean isHeld(String id) {
        Objects.requireNonNull(id, "id");
        return ID.matcher(id).matches() && !RESERVED.contains(id);
    }

    /** Whether a saga recorded under {@code currency} moves the island's own money. */
    public static boolean isPrimary(String currency) {
        Objects.requireNonNull(currency, "currency");
        return currency.equalsIgnoreCase(PRIMARY_MONEY) || currency.equalsIgnoreCase(PRIMARY_COLUMN);
    }

    /** The bank column a saga recorded under {@code currency} moves. */
    public static String columnOf(String currency) {
        return isPrimary(currency) ? PRIMARY_COLUMN : currency.toLowerCase(Locale.ROOT);
    }

    /** The scale of the column a saga recorded under {@code currency} moves. */
    public static int scaleOf(String currency) {
        return isPrimary(currency) ? PRIMARY_SCALE : 0;
    }
}
