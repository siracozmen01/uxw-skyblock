package com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop;

import com.uxplima.uxmskyblock.core.application.economy.ExternalWalletPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** A shop class that charges the player's wallet itself, outside the saga, which the rule must refuse. */
public final class WalletOutsideTheSagaFixture {

    private final ExternalWalletPort wallet;

    public WalletOutsideTheSagaFixture(ExternalWalletPort wallet) {
        this.wallet = wallet;
    }

    public boolean charge(PlayerUuid player, long amountMinorUnits) {
        return wallet.withdraw(player, amountMinorUnits);
    }
}
