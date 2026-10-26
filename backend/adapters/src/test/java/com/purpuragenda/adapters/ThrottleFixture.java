package com.purpuragenda.adapters;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Synthetic fixtures: a per-test schema, the application role, and an injectable captured database time. */
final class ThrottleFixture {
    final MigrationTest migration = new MigrationTest();
    final DriverManagerDataSource source;

    ThrottleFixture() {
        migration.fresh();
        source = source("p17_throttle");
    }

    DriverManagerDataSource source(String application) {
        var value = new DriverManagerDataSource(MigrationTest.jdbcUrl, MigrationTest.APP, MigrationTest.appPassword);
        Properties properties = new Properties();
        properties.setProperty("currentSchema", migration.schema + ",public");
        properties.setProperty("ApplicationName", application);
        value.setConnectionProperties(properties);
        return value;
    }

    void close() { migration.cleanup(); }

    @FunctionalInterface interface SqlHook { void before(String sql, int call) throws SQLException; }

    /** Every {@code SELECT clock_timestamp()} on connections from the returned source reports the reference value. */
    static DataSource at(DataSource target, AtomicReference<Instant> clock) {
        return new DelegatingDataSource(target) {
            @Override public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                return proxy(Connection.class, (p, m, a) -> {
                    Object result = invoke(connection, m, a);
                    if (!m.getName().equals("createStatement")) return result;
                    return proxy(Statement.class, (sp, sm, sa) -> {
                        Object rows = invoke(result, sm, sa);
                        // A null reference passes the real database clock through.
                        if (!sm.getName().equals("executeQuery") || !"SELECT clock_timestamp()".equals(sa[0]) || clock.get() == null) return rows;
                        return proxy(ResultSet.class, (rp, rm, ra) ->
                                rm.getName().equals("getTimestamp") && Integer.valueOf(1).equals(ra[0])
                                        ? Timestamp.from(clock.get()) : invoke(rows, rm, ra));
                    });
                });
            }
        };
    }

    /** Runs the hook before each prepared statement execution; a thrown SQLException simulates a provider failure. */
    static DataSource failing(DataSource target, SqlHook hook) {
        return new DelegatingDataSource(target) {
            int calls;
            @Override public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                return proxy(Connection.class, (p, m, a) -> {
                    Object result = invoke(connection, m, a);
                    if (!m.getName().equals("prepareStatement")) return result;
                    String sql = (String) a[0];
                    return proxy(PreparedStatement.class, (sp, sm, sa) -> {
                        if (sm.getName().startsWith("execute")) hook.before(sql, ++calls);
                        return invoke(result, sm, sa);
                    });
                });
            }
        };
    }

    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
