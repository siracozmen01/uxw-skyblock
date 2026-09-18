package com.uxplima.uxmskyblock.core.application.economy;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/**
 * Outbound port abstracting an external player wallet (e.g. Vault / VaultUnlocked).
 */
public interface ExternalWalletPort {

    boolean hasFunds(PlayerUuid playerUuid, long amountMinorUnits);

    boolean withdraw(PlayerUuid playerUuid, long amountMinorUnits);

    boolean deposit(PlayerUuid playerUuid, long amountMinorUnits);
}
