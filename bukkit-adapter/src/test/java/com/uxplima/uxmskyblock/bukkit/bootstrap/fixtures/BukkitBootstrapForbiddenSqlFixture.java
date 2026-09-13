package com.uxplima.uxmskyblock.bukkit.bootstrap.fixtures;

import java.sql.Connection;

public final class BukkitBootstrapForbiddenSqlFixture {

    @SuppressWarnings({"NullAway.Init", "FieldCanBeLocal"})
    private Connection connection;

    public void use(Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }
}
