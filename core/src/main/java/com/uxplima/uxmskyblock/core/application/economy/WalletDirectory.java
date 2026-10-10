package com.uxplima.uxmskyblock.core.application.economy;

import java.util.Objects;
import java.util.Optional;

/**
 * The wallet each currency is taken from and paid into, named by what a saga is recorded under.
 *
 * <p>The island bank took the island's own money and nothing else, so every saga had one wallet. It holds every
 * currency the operator lists now, and each comes from a wallet of its own: the economy plugin, the player's
 * experience, an item in their inventory.
 */
@FunctionalInterface
public interface WalletDirectory {

    /** The wallet of {@code currency}, or nothing when this server has none for it. */
    Optional<ExternalWalletPort> walletFor(String currency);

    /** One wallet for every currency, which is the bank as it was before it held more than one. */
    static WalletDirectory only(ExternalWalletPort wallet) {
        Objects.requireNonNull(wallet, "wallet must not be null");
        return currency -> Optional.of(wallet);
    }
}
