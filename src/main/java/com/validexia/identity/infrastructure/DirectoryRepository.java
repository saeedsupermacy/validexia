package com.validexia.identity.infrastructure;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;

/**
 * The application's only door to the identity directory. Every method calls a SECURITY DEFINER
 * function from migrations V1/V3; the application role holds no direct grants on the underlying
 * tables (M02-1 v2.6 §1/§5). Lookups are keyed by the verified token subject, never by
 * client-supplied identifiers.
 */
@Repository
public class DirectoryRepository {

  public record DirectoryUser(
      UUID userId,
      String status,
      boolean banned,
      boolean locked,
      boolean deprovisioned,
      @Nullable Instant tokensValidAfter) {}

  public record MembershipView(UUID tenantId, String tenantName, String role, String status) {}

  private final JdbcTemplate jdbc;

  public DirectoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<DirectoryUser> findUser(String subject) {
    List<DirectoryUser> rows =
        jdbc.query(
            "SELECT user_id, status, banned, locked, deprovisioned, tokens_valid_after"
                + " FROM public.find_user_by_subject(?)",
            (rs, rowNum) ->
                new DirectoryUser(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("status"),
                    rs.getBoolean("banned"),
                    rs.getBoolean("locked"),
                    rs.getBoolean("deprovisioned"),
                    Optional.ofNullable(rs.getObject("tokens_valid_after", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant)
                        .orElse(null)),
            subject);
    return rows.stream().findFirst();
  }

  public List<MembershipView> listMemberships(String subject) {
    return jdbc.query(
        "SELECT tenant_id, tenant_name, role, membership_status FROM public.list_memberships(?)",
        (rs, rowNum) ->
            new MembershipView(
                rs.getObject("tenant_id", UUID.class),
                rs.getString("tenant_name"),
                rs.getString("role"),
                rs.getString("membership_status")),
        subject);
  }

  /** Idempotent lazy provisioning. Never modifies status or flags. */
  public UUID provisionUser(String subject, @Nullable String email, @Nullable String displayName) {
    return jdbc.queryForObject(
        "SELECT public.provision_user(?, ?, ?)", UUID.class, subject, email, displayName);
  }

  public void syncUserProfile(
      String subject, @Nullable String email, @Nullable String displayName, Instant eventTs) {
    callVoid(
        "SELECT public.sync_user_profile(?, ?, ?, ?)",
        ps -> {
          ps.setString(1, subject);
          setNullableString(ps, 2, email);
          setNullableString(ps, 3, displayName);
          ps.setObject(4, toOffset(eventTs));
        });
  }

  /** Unified lifecycle applier. A null component means "this event says nothing about it". */
  public void applyLifecycle(
      String subject,
      @Nullable String status,
      @Nullable Boolean banned,
      @Nullable Boolean locked,
      @Nullable Boolean deprovisioned,
      Instant eventTs) {
    callVoid(
        "SELECT public.apply_user_lifecycle(?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setString(1, subject);
          setNullableString(ps, 2, status);
          setNullableBoolean(ps, 3, banned);
          setNullableBoolean(ps, 4, locked);
          setNullableBoolean(ps, 5, deprovisioned);
          ps.setObject(6, toOffset(eventTs));
        });
  }

  /** Snapshot applier for the JIT resync: same flag rules, never touches status. */
  public void applyLifecycleSnapshot(
      String subject, boolean banned, boolean locked, boolean deprovisioned, Instant updatedAt) {
    callVoid(
        "SELECT public.apply_user_lifecycle_snapshot(?, ?, ?, ?, ?)",
        ps -> {
          ps.setString(1, subject);
          ps.setBoolean(2, banned);
          ps.setBoolean(3, locked);
          ps.setBoolean(4, deprovisioned);
          ps.setObject(5, toOffset(updatedAt));
        });
  }

  public void mergeUsers(String survivingSubject, String deletedSubject, Instant eventTs) {
    callVoid(
        "SELECT public.merge_users(?, ?, ?)",
        ps -> {
          ps.setString(1, survivingSubject);
          ps.setString(2, deletedSubject);
          ps.setObject(3, toOffset(eventTs));
        });
  }

  /** Atomic 60-second resync claim (one conditional UPDATE in the database). */
  public boolean claimResync(String subject) {
    Boolean claimed = jdbc.queryForObject("SELECT public.claim_resync(?)", Boolean.class, subject);
    return Boolean.TRUE.equals(claimed);
  }

  public void recordSessionRevocation(String sid, String subject, Instant expiresAt) {
    callVoid(
        "SELECT public.record_session_revocation(?, ?, ?)",
        ps -> {
          ps.setString(1, sid);
          ps.setString(2, subject);
          ps.setObject(3, toOffset(expiresAt));
        });
  }

  public boolean isSessionRevoked(String sid) {
    Boolean revoked =
        jdbc.queryForObject("SELECT public.check_session_revoked(?)", Boolean.class, sid);
    return Boolean.TRUE.equals(revoked);
  }

  /** Returns true only for the first delivery of a webhook event. */
  public boolean recordWebhookEvent(String svixId, String eventType) {
    Boolean first =
        jdbc.queryForObject(
            "SELECT public.record_webhook_event(?, ?)", Boolean.class, svixId, eventType);
    return Boolean.TRUE.equals(first);
  }

  public void markWebhookEvent(String svixId, String outcome) {
    callVoid(
        "SELECT public.mark_webhook_event(?, ?)",
        ps -> {
          ps.setString(1, svixId);
          ps.setString(2, outcome);
        });
  }

  private interface Binder {
    void bind(PreparedStatement ps) throws java.sql.SQLException;
  }

  private void callVoid(String sql, Binder binder) {
    jdbc.execute(
        connection -> {
          PreparedStatement ps = connection.prepareStatement(sql);
          binder.bind(ps);
          return ps;
        },
        (PreparedStatementCallback<Void>)
            ps -> {
              ps.execute();
              return null;
            });
  }

  private static OffsetDateTime toOffset(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void setNullableString(PreparedStatement ps, int index, @Nullable String value)
      throws java.sql.SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  private static void setNullableBoolean(PreparedStatement ps, int index, @Nullable Boolean value)
      throws java.sql.SQLException {
    if (value == null) {
      ps.setNull(index, Types.BOOLEAN);
    } else {
      ps.setBoolean(index, value);
    }
  }
}
