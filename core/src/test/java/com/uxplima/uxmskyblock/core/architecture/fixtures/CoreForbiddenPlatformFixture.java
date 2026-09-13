package com.uxplima.uxmskyblock.core.architecture.fixtures;

import org.bukkit.stub.StubBukkitType;

public final class CoreForbiddenPlatformFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubBukkitType platformType = new StubBukkitType();

    public StubBukkitType getPlatformType() {
        return platformType;
    }
}
