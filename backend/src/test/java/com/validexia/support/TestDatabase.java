package com.validexia.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Database bootstrap for integration tests (M02-1 v2.6 §9).
 *
 * <p>Default path (CI): a real PostgreSQL via Testcontainers, image pinned to {@code postgres:17}.
 * Local fallback: when {@code VALIDEXIA_TEST_DB_URL} (plus {@code _USER}/{@code _PASSWORD}) is
 * set, that externally managed database is used instead — same migrations, same assertions. The
 * database named by the URL must be freshly created and disposable.
 *
 * <p>Bootstrap order matters and mirrors deployment: Flyway migrates as the ADMIN (migrator)
 * connection, then the admin sets the application role's password, and only then is the Spring
 * context allowed to start — its datasource connects as {@code validexia_app}, so every
 * integration assertion runs under the restricted role's real permissions.
 */
public final class TestDatabase {

  public static final String APP_USER = "validexia_app";
  public static final String APP_PASSWORD = "validexia-app-test-password";

  private static PostgreSQLContainer container;
  private static String adminUrl;
  private static String adminUser;
  private static String adminPassword;
  private static boolean bootstrapped;

  private TestDatabase() {}

  public static synchronized Map<String, String> springProperties() {
    bootstrap();
    String appUrl = adminUrl;
    return Map.of(
        "spring.datasource.url", appUrl,
        "spring.datasource.username", APP_USER,
        "spring.datasource.password", APP_PASSWORD,
        "spring.flyway.enabled", "false",
        "spring.datasource.hikari.maximum-pool-size", "1");
  }

  public static synchronized void bootstrap() {
    if (bootstrapped) {
      return;
    }
    String externalUrl = System.getenv("VALIDEXIA_TEST_DB_URL");
    if (externalUrl != null && !externalUrl.isBlank()) {
      adminUrl = externalUrl;
      adminUser = envOrDefault("VALIDEXIA_TEST_DB_USER", "postgres");
      adminPassword = envOrDefault("VALIDEXIA_TEST_DB_PASSWORD", "");
    } else {
      container =
          new PostgreSQLContainer("postgres:17")
              .withDatabaseName("validexia_test")
              .withUsername("postgres")
              .withPassword("postgres-test");
      container.start();
      adminUrl = container.getJdbcUrl();
      adminUser = container.getUsername();
      adminPassword = container.getPassword();
    }
    Flyway.configure()
        .dataSource(adminUrl, adminUser, adminPassword)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
        Statement statement = connection.createStatement()) {
      statement.execute("ALTER ROLE validexia_app PASSWORD '" + APP_PASSWORD + "'");
    } catch (Exception e) {
      throw new IllegalStateException("Failed to set the application role password", e);
    }
    bootstrapped = true;
  }

  /** An admin (migrator-equivalent) JdbcTemplate for seeding and tampering in tests. */
  public static JdbcTemplate adminJdbc() {
    bootstrap();
    return new JdbcTemplate(new DriverManagerDataSource(adminUrl, adminUser, adminPassword));
  }

  /** A JdbcTemplate connected as the restricted application role — the test's point of view. */
  public static JdbcTemplate appJdbc() {
    bootstrap();
    return new JdbcTemplate(new DriverManagerDataSource(adminUrl, APP_USER, APP_PASSWORD));
  }

  /** The application role's datasource (same database, restricted credentials). */
  public static DriverManagerDataSource appDataSource() {
    bootstrap();
    return new DriverManagerDataSource(adminUrl, APP_USER, APP_PASSWORD);
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}
