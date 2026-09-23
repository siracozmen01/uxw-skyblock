package com.uxplima.uxmskyblock.persistence.testfixture;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import javax.sql.DataSource;

import com.uxplima.uxmlib.storage.sql.Database;

/**
 * Connections that write down every statement they send, as {@code query:}, {@code update:} or
 * {@code batch:} followed by the statement's text, so a test can say how many round trips a piece of
 * persistence costs and which ones.
 */
public final class CountingConnections {

    private CountingConnections() {}

    /** {@code real}, writing down what it sends into {@code sent}. */
    public static Connection wrap(Connection real, List<String> sent) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = invoke(method, real, args);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement ps) {
                        String sql = ((String) args[0]).strip();
                        return Proxy.newProxyInstance(
                                PreparedStatement.class.getClassLoader(),
                                new Class<?>[] {PreparedStatement.class},
                                (p, m, a) -> {
                                    switch (m.getName()) {
                                        case "executeQuery" -> sent.add("query:" + sql);
                                        case "executeUpdate" -> sent.add("update:" + sql);
                                        case "executeBatch" -> sent.add("batch:" + sql);
                                        default -> {}
                                    }
                                    return invoke(m, ps, a);
                                });
                    }
                    return result;
                });
    }

    /** A database over the same pool as {@code real} whose every connection writes into {@code sent}. */
    public static Database over(Database real, List<String> sent) {
        DataSource pool = real.dataSource();
        DataSource counting = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(method, pool, args);
                    return result instanceof Connection conn ? wrap(conn, sent) : result;
                });
        return Database.adopt(counting, real.dialect());
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
