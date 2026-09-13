package com.uxplima.uxmskyblock.core.architecture.fixtures;

import com.uxplima.uxmskyblock.persistence.stub.StubPersistenceAdapterType;

public final class CoreForbiddenAdapterFixture {

    @SuppressWarnings("FieldCanBeLocal")
    private final StubPersistenceAdapterType adapter = new StubPersistenceAdapterType();

    public StubPersistenceAdapterType getAdapter() {
        return adapter;
    }
}
