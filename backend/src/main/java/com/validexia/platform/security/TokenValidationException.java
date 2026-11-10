package com.validexia.platform.security;

/** Any session-token rejection. Always surfaces to the client as 401 UNAUTHENTICATED. */
public class TokenValidationException extends RuntimeException {

  public TokenValidationException(String message) {
    super(message);
  }

  public TokenValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
