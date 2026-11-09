package com.validexia.platform.errors;

import org.springframework.http.HttpStatus;

/** An error that maps directly onto the API error body {@code {code, message}}. */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException unauthenticated(String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message);
  }

  public static ApiException forbidden(String code, String message) {
    return new ApiException(HttpStatus.FORBIDDEN, code, message);
  }

  public static ApiException validation(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }
}
