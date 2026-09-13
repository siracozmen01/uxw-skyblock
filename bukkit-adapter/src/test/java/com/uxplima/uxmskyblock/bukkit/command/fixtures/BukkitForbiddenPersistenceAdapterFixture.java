package com.uxplima.uxmskyblock.bukkit.command.fixtures;

import com.uxplima.uxmskyblock.persistence.stub.StubPersistenceAdapterType;

public final class BukkitForbiddenPersistenceAdapterFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubPersistenceAdapterType persistenceAdapter = new StubPersistenceAdapterType();

    public StubPersistenceAdapterType getPersistenceAdapter() {
        return persistenceAdapter;
    }
}
