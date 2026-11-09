package com.validexia.identity.api;

import com.validexia.identity.application.SessionService;
import com.validexia.platform.security.RequestContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logout (M02-1 v2.6 §2): the session id is denylisted locally until well past any outstanding
 * token's expiry, and Clerk is asked to revoke the session too (best effort — the local denylist
 * is authoritative for this application).
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

  private final SessionService sessions;

  public AuthController(SessionService sessions) {
    this.sessions = sessions;
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout() {
    sessions.logout(RequestContext.require());
    return ResponseEntity.noContent().build();
  }
}
