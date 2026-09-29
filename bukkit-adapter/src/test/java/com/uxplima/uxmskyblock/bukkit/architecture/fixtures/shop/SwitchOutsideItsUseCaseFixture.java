package com.uxplima.uxmskyblock.bukkit.architecture.fixtures.shop;

import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.profile.ProfileSwitchPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;

/** A class that ends a profile switch itself, outside the switch use case, which the rule must refuse. */
public final class SwitchOutsideItsUseCaseFixture {

    private final ProfileSwitchPort switches;

    public SwitchOutsideItsUseCaseFixture(ProfileSwitchPort switches) {
        this.switches = switches;
    }

    public void abandon(UUID operation, PlayerUuid player) {
        switches.abortSwitch(operation, player, "shop");
    }
}
