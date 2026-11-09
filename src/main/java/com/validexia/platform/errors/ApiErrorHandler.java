package com.validexia.platform.errors;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every API error body is exactly {@code {code, message}} (M02-1 v2.6 §5). */
@RestControllerAdvice
public class ApiErrorHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, String>> api(ApiException e) {
    return body(e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
    return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request body is not valid JSON");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, String>> notFound(NoResourceFoundException e) {
    return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> unexpected(Exception e) {
    log.error("Unhandled exception", e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Internal error");
  }

  static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
  }

  /** Serializes the same shape for the authentication filter, which runs before the advice. */
  public static String bodyJson(String code, String message) {
    return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
