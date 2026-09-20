package com.uxplima.uxmskyblock.persistence.architecture.fixtures;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class PersistenceForbiddenRawStatementFixture {

    public void executeRawSql(Connection conn, String dynamicInput) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT * FROM users WHERE name = '" + dynamicInput + "'");
        }
    }
}
