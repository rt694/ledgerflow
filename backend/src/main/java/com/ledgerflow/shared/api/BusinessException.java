package com.ledgerflow.shared.api;

import org.springframework.http.HttpStatus;

/** Carries only safe, deliberately chosen client-facing messages. */
public class BusinessException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public BusinessException(HttpStatus status, String code, String message) {
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

  public static BusinessException notFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.");
  }

  public static BusinessException forbidden() {
    return new BusinessException(
        HttpStatus.FORBIDDEN, "FORBIDDEN", "Your organization role does not allow this action.");
  }

  public static BusinessException conflict(String message) {
    return new BusinessException(HttpStatus.CONFLICT, "CONFLICT", message);
  }

  public static BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }
}
