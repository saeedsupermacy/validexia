package com.validexia.platform.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Statement-order proof (M02-1 v2.6 §1): a recording proxy around the DataSource shows the {@code
 * set_config} pair is the FIRST statement of the transaction, carrying the exact context values —
 * RLS never evaluates a query before its context exists.
 */
class TenantContextOrderingTest {

  private record Recorded(String sql, java.util.Map<Integer, Object> params) {}

  @Test
  void setConfigPairIsTheFirstStatementOfEveryTransaction() {
    List<Recorded> statements = new ArrayList<>();
    DataSource recording = recordingDataSource(TestDatabase.appDataSource(), statements);
    TenantTransactionManager manager = new TenantTransactionManager(recording);
    JdbcTemplate jdbc = new JdbcTemplate(recording);

    TenantContext.set("tenant-xyz", "user-abc");
    try {
      // A bind-variable query: JdbcTemplate serves no-arg queries with a plain
      // Statement, which this PreparedStatement-recording proxy cannot see.
      new TransactionTemplate(manager)
          .executeWithoutResult(tx -> jdbc.queryForObject("SELECT ?", Integer.class, 1));
    } finally {
      TenantContext.clear();
    }

    assertTrue(statements.size() >= 2, "expected at least the set_config and the query");
    Recorded first = statements.get(0);
    assertTrue(
        first.sql().contains("set_config('app.tenant_id'"),
        "first statement must establish context, was: " + first.sql());
    assertEquals("tenant-xyz", first.params().get(1));
    assertEquals("user-abc", first.params().get(2));
    assertEquals("SELECT ?", statements.get(1).sql());
  }

  private static DataSource recordingDataSource(
      DriverManagerDataSource delegate, List<Recorded> sink) {
    return (DataSource)
        Proxy.newProxyInstance(
            TenantContextOrderingTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = method.invoke(delegate, args);
              if (result instanceof Connection connection) {
                return wrapConnection(connection, sink);
              }
              return result;
            });
  }

  private static Connection wrapConnection(Connection delegate, List<Recorded> sink) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          Object result = method.invoke(delegate, args);
          if (result instanceof PreparedStatement ps
              && ("prepareStatement".equals(method.getName())
                  || "prepareCall".equals(method.getName()))) {
            return wrapStatement(ps, String.valueOf(args[0]), sink);
          }
          return result;
        };
    return (Connection)
        Proxy.newProxyInstance(
            TenantContextOrderingTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            handler);
  }

  private static PreparedStatement wrapStatement(
      PreparedStatement delegate, String sql, List<Recorded> sink) {
    Recorded recorded = new Recorded(sql, new java.util.TreeMap<>());
    InvocationHandler handler =
        (proxy, method, args) -> {
          if (method.getName().startsWith("set")
              && args != null
              && args.length >= 2
              && args[0] instanceof Integer index) {
            recorded.params().put(index, args[1]);
          }
          if (method.getName().startsWith("execute")) {
            sink.add(recorded);
          }
          return method.invoke(delegate, args);
        };
    return (PreparedStatement)
        Proxy.newProxyInstance(
            TenantContextOrderingTest.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            handler);
  }
}
