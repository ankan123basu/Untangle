package dev.untangle.common.error;

import dev.untangle.common.logging.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single place where exceptions become HTTP responses. Every error body is an RFC 9457 Problem
 * Details document ({@code application/problem+json}, ADR-007):
 *
 * <pre>{@code
 * {
 *   "type": "urn:untangle:problem:validation-failed",
 *   "title": "Validation failed",
 *   "status": 400,
 *   "detail": "Request has 1 invalid field",
 *   "instance": "/api/v1/agents",
 *   "code": "VALIDATION_FAILED",
 *   "traceId": "4f1c...",          // same as the X-Request-Id response header
 *   "errors": [{"field": "email", "message": "must be a well-formed email address"}]
 * }
 * }</pre>
 *
 * <p>Security rule: no stack traces, SQL, class names or rejected values (they could be passwords)
 * ever reach the client. Unexpected errors are logged with the trace id and answered with a generic
 * 500.
 *
 * <p>Spring Security's entry point and access-denied handler delegate here too, so 401/403
 * responses have exactly the same shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** One invalid field. The rejected value is deliberately not echoed back. */
  public record FieldViolation(String field, String message) {}

  // ---- Our own business errors ------------------------------------------------------------

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
    ProblemDetail problem = problem(ex.getCode(), ex.getMessage());
    ex.getProperties().forEach(problem::setProperty);
    ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getCode().status());
    if (ex instanceof RateLimitExceededException rateLimited) {
      response.header(HttpHeaders.RETRY_AFTER, Long.toString(rateLimited.getRetryAfterSeconds()));
    }
    return response.body(problem);
  }

  // ---- Security ---------------------------------------------------------------------------

  @ExceptionHandler(AuthenticationException.class)
  ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
    ErrorCode code;
    if (ex instanceof DisabledException) {
      code = ErrorCode.AUTH_ACCOUNT_DISABLED;
    } else if (ex.getMessage() != null
        && ex.getMessage().toLowerCase(Locale.ROOT).contains("expired")) {
      code = ErrorCode.AUTH_TOKEN_EXPIRED;
    } else {
      code = ErrorCode.AUTH_UNAUTHENTICATED;
    }
    return ResponseEntity.status(code.status())
        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        .body(problem(code, code.title()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
    return respond(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.title());
  }

  // ---- Persistence ------------------------------------------------------------------------

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ProblemDetail> handleVersionConflict(ObjectOptimisticLockingFailureException ex) {
    return respond(
        ErrorCode.VERSION_CONFLICT,
        "This record was updated by someone else. Refresh to see the latest version.");
  }

  // ---- Validation -------------------------------------------------------------------------

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldViolation> violations =
        ex.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
            .toList();
    return validationFailed(violations);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
    List<FieldViolation> violations =
        ex.getConstraintViolations().stream()
            .map(v ->
                new FieldViolation(lastPathNode(v.getPropertyPath().toString()), v.getMessage()))
            .toList();
    return validationFailed(violations);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return validationFailed(List.of(new FieldViolation(ex.getName(), "has an invalid value")));
  }

  /**
   * Malformed JSON, or a value that can't be converted (e.g. {@code "priority": "high"} for an
   * enum). The client gets a 400 with the field name, never Jackson's internal message.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String field = JsonErrorPaths.fieldOf(ex);
    if (field == null) {
      ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Malformed JSON request body");
      return ResponseEntity.badRequest().body(problem);
    }
    return validationFailed(List.of(new FieldViolation(field, "has an invalid value")));
  }

  /**
   * Framework-generated errors (404 unknown route, 405, 415, ...) already arrive as a {@link
   * ProblemDetail}; we only add our {@code code} and {@code traceId}.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ProblemDetail problem =
        body instanceof ProblemDetail existing ? existing : ProblemDetail.forStatus(status);
    ErrorCode code =
        switch (status.value()) {
          case 400 -> ErrorCode.VALIDATION_FAILED;
          case 404 -> ErrorCode.NOT_FOUND;
          case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
          case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
          case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
          default ->
              status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
    decorate(problem, code);
    return new ResponseEntity<>(problem, headers, status);
  }

  // ---- Last resort ------------------------------------------------------------------------

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return respond(
        ErrorCode.INTERNAL_ERROR,
        "An unexpected error occurred. Quote the traceId when reporting it.");
  }

  // ---- Helpers ----------------------------------------------------------------------------

  private ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail) {
    return ResponseEntity.status(code.status()).body(problem(code, detail));
  }

  private ResponseEntity<Object> validationFailed(List<FieldViolation> violations) {
    String detail =
        violations.size() == 1
            ? "Request has 1 invalid field"
            : "Request has " + violations.size() + " invalid fields";
    ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, detail);
    problem.setProperty("errors", violations);
    return ResponseEntity.badRequest().body(problem);
  }

  static ProblemDetail problem(ErrorCode code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
    decorate(problem, code);
    return problem;
  }

  private static void decorate(ProblemDetail problem, ErrorCode code) {
    problem.setType(
        URI.create(
            "urn:untangle:problem:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    problem.setTitle(code.title());
    problem.setProperty("code", code.name());
    problem.setProperty("traceId", MDC.get(RequestIdFilter.MDC_KEY));
  }

  private static String lastPathNode(String path) {
    int dot = path.lastIndexOf('.');
    return dot >= 0 ? path.substring(dot + 1) : path;
  }
}
