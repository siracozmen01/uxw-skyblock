package com.uxplima.uxmskyblock.bukkit.architecture.fixtures;

import java.sql.Connection;

public final class BukkitForbiddenSqlFixture {

    @SuppressWarnings({"NullAway.Init", "FieldCanBeLocal"})
    private Connection connection;

    public void use(Connection connection) {
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }
}
