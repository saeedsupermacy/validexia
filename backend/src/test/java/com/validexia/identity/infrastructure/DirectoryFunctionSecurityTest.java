package com.validexia.identity.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.validexia.support.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The functions-only access model, proven from the application role's point of view (M02-1 v2.6
 * §1/§4): direct table access is denied, and a hostile session search_path / same-named object
 * cannot divert the definer functions.
 */
class DirectoryFunctionSecurityTest {

  private final JdbcTemplate app = TestDatabase.appJdbc();
  private final JdbcTemplate admin = TestDatabase.adminJdbc();
  private final DirectoryRepository repository = new DirectoryRepository(app);

  @Test
  void directTableAccessIsDenied() {
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.users", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.tenants", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.memberships", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.revoked_sessions", Integer.class));
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.webhook_events", Integer.class));
  }

  @Test
  void functionsWorkWhileTablesStayClosed() {
    String subject = "user_sec_" + UUID.randomUUID();
    UUID id = repository.provisionUser(subject, "sec@example.test", "Sec");
    assertTrue(repository.findUser(subject).isPresent());
    assertEquals(id, repository.findUser(subject).orElseThrow().userId());
    assertTrue(repository.listMemberships(subject).isEmpty());
  }

  @Test
  void hostileSearchPathCannotDivertDefinerFunctions() {
    String subject = "user_hostile_" + UUID.randomUUID();
    repository.provisionUser(subject, "real@example.test", null);
    admin.execute("CREATE SCHEMA IF NOT EXISTS evil");
    admin.execute("CREATE TABLE IF NOT EXISTS evil.users (external_subject text, email text)");
    admin.update(
        "INSERT INTO evil.users VALUES ('"
            + subject
            + "', 'attacker@evil.test')"
            + " ON CONFLICT DO NOTHING");
    // Even with the hostile schema first in the SESSION path, the qualified definer call with
    // its pinned pg_catalog, pg_temp path reads the real directory.
    app.execute("SET search_path = evil, public");
    try {
      var user = repository.findUser(subject);
      assertTrue(user.isPresent());
      assertEquals("active", user.orElseThrow().status());
    } finally {
      app.execute("RESET search_path");
    }
  }
}
