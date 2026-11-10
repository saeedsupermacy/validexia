package com.validexia.identity.application;

import com.validexia.identity.infrastructure.DirectoryRepository;
import com.validexia.platform.config.ClerkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Locked-only just-in-time resync (M02-1 v2.6 safeguard spec). Clerk's temporary lockouts lapse
 * automatically with no webhook announcing it, so a locally-locked user would otherwise be locked
 * out forever. Three phases, never sharing a transaction:
 *
 * <ol>
 *   <li><b>Claim</b> — one atomic conditional UPDATE in the database ({@code claim_resync}); the
 *       claim commits with that statement, and a failed fetch still consumes the window.
 *   <li><b>Fetch</b> — the Clerk API call happens outside any database transaction; this service
 *       is invoked from the authentication filter, before any request transaction exists.
 *   <li><b>Conditional apply</b> — the snapshot is applied per-flag by timestamp (never touching
 *       status), so a delayed response cannot overwrite newer webhook state.
 * </ol>
 *
 * Triggered ONLY when a request is blocked solely by {@code locked}. Any failure leaves the user
 * locked (fail closed); the caller re-reads state and decides.
 */
@Service
public class LockedResyncService {

  private static final Logger log = LoggerFactory.getLogger(LockedResyncService.class);

  private final DirectoryRepository directory;
  private final ClerkBackendClient clerk;
  private final ClerkProperties properties;

  public LockedResyncService(
      DirectoryRepository directory, ClerkBackendClient clerk, ClerkProperties properties) {
    this.directory = directory;
    this.clerk = clerk;
    this.properties = properties;
  }

  public void tryResync(String subject) {
    if (!properties.hasSecretKey()) {
      return;
    }
    boolean claimed;
    try {
      claimed = directory.claimResync(subject);
    } catch (Exception e) {
      log.warn("Resync claim failed for a locked user: {}", e.getMessage());
      return;
    }
    if (!claimed) {
      return;
    }
    clerk
        .fetchUser(subject)
        .ifPresent(
            snapshot -> {
              try {
                directory.applyLifecycleSnapshot(
                    subject,
                    snapshot.banned(),
                    snapshot.locked(),
                    snapshot.deprovisioned(),
                    snapshot.updatedAt());
              } catch (Exception e) {
                log.warn("Resync snapshot apply failed: {}", e.getMessage());
              }
            });
  }
}
