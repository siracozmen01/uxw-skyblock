package com.uxplima.uxmskyblock.core.architecture.fixtures;

import java.sql.Connection;

public final class CoreForbiddenPersistenceFixture {

    @SuppressWarnings({"NullAway.Init", "FieldCanBeLocal"})
    private Connection connection;

    public void useConnection(Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }
}
