package com.validexia.vendors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.validexia.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V12 schema shape: both vendor tables exist under the vendors owner role with row-level security
 * enabled AND forced, the application role has no direct table access, and every vendor function is
 * owned by the vendors owner role (so FORCE RLS binds the SECURITY DEFINER functions too).
 */
class VendorsSchemaTest {

  private final JdbcTemplate app = TestDatabase.appJdbc();
  private final JdbcTemplate admin = TestDatabase.adminJdbc();

  @Test
  void vendorTablesExistWithRlsEnabledAndForced() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_class WHERE relname IN ('vendors',"
                + " 'vendor_dependent_systems') AND relrowsecurity = true"
                + " AND relforcerowsecurity = true",
            Integer.class);
    assertEquals(2, count);
  }

  @Test
  void vendorTablesAreOwnedByTheVendorsOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_tables WHERE tablename IN ('vendors',"
                + " 'vendor_dependent_systems') AND tableowner = 'validexia_vendors_owner'",
            Integer.class);
    assertEquals(2, count);
  }

  @Test
  void appRoleHasNoDirectTableAccess() {
    assertThrows(
        DataAccessException.class,
        () -> app.queryForObject("SELECT count(*) FROM public.vendors", Integer.class));
    assertThrows(
        DataAccessException.class,
        () ->
            app.update(
                "INSERT INTO public.vendors (tenant_id, name, code) VALUES (NULL, 'x', 'y')"));
  }

  @Test
  void vendorFunctionsAreOwnedByTheVendorsOwner() {
    Integer count =
        admin.queryForObject(
            "SELECT count(*) FROM pg_proc p JOIN pg_roles r ON r.oid = p.proowner"
                + " WHERE p.proname IN ('create_vendor', 'update_vendor', 'terminate_vendor',"
                + " 'get_vendor', 'list_vendors', 'clear_vendor_dependent_systems',"
                + " 'add_vendor_dependent_system', 'list_vendor_dependent_systems')"
                + " AND r.rolname = 'validexia_vendors_owner'",
            Integer.class);
    assertEquals(8, count);
  }
}
