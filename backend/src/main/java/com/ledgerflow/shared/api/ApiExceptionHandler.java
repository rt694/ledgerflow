package com.ledgerflow.shared.api;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = problem(status, "Check the request fields.", request);
    List<FieldViolation> errors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
            .distinct()
            .sorted(
                java.util.Comparator.comparing(FieldViolation::field)
                    .thenComparing(FieldViolation::message))
            .toList();
    problem.setProperty("errors", errors);
    return new ResponseEntity<>(problem, headers, status);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // Do not expose parser messages, rejected values, or internal exception details.
    return new ResponseEntity<>(problem(status, safeMessage(status), request), headers, status);
  }

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<Object> handleBusiness(BusinessException exception, WebRequest request) {
    var body = problem(exception.status(), exception.getMessage(), request);
    body.setProperty("code", exception.code());
    return new ResponseEntity<>(body, exception.status());
  }

  @ExceptionHandler({
    org.springframework.dao.DataIntegrityViolationException.class,
    org.springframework.orm.ObjectOptimisticLockingFailureException.class
  })
  public ResponseEntity<Object> handleConflict(Exception exception, WebRequest request) {
    var status = HttpStatus.CONFLICT;
    return new ResponseEntity<>(
        problem(status, "The change conflicts with existing data. Reload and try again.", request),
        status);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
    // Log only the exception class; messages can contain credentials or submitted data.
    logger.error("Unhandled request exception: " + exception.getClass().getName());
    HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
    return new ResponseEntity<>(problem(status, safeMessage(status), request), status);
  }

  private ProblemDetail problem(HttpStatusCode status, String message, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
    if (request instanceof ServletWebRequest servletRequest) {
      problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
    }
    problem.setProperty("code", "HTTP_" + status.value());
    return problem;
  }

  private String safeMessage(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> "The request is invalid.";
      case 404 -> "The requested resource was not found.";
      case 405 -> "This HTTP method is not supported for this resource.";
      case 415 -> "The request content type is not supported.";
      default ->
          status.is5xxServerError()
              ? "An unexpected error occurred."
              : "The request could not be processed.";
    };
  }

  public record FieldViolation(String field, String message) {}
}
