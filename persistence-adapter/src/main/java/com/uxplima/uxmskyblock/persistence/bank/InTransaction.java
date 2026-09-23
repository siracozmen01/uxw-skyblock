package com.uxplima.uxmskyblock.persistence.bank;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A write that must land in the same transaction as a bank charge, or neither lands.
 *
 * <p>It runs once the balance has moved and before anything is committed, on the charge's own
 * connection. Answering false rolls the charge back with it.
 */
@FunctionalInterface
public interface InTransaction {

    boolean apply(Connection connection) throws SQLException;
}
