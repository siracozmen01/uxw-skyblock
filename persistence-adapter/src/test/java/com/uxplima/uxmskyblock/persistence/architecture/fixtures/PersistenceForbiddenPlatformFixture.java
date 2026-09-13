package com.uxplima.uxmskyblock.persistence.architecture.fixtures;

import org.bukkit.stub.StubBukkitType;

public final class PersistenceForbiddenPlatformFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubBukkitType platformType = new StubBukkitType();

    public StubBukkitType getPlatformType() {
        return platformType;
    }
}
