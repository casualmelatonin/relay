package com.relay.coordination.api;

import com.relay.coordination.domain.DomainException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    basePackages = {
      "com.relay.coordination.api",
      "com.relay.coordination.mcp",
      "com.relay.coordination.chunk"
    })
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {
  @ExceptionHandler(DomainException.class)
  ProblemDetail domain(DomainException exception) {
    return problem(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail validation(MethodArgumentNotValidException exception) {
    ProblemDetail result = problem(422, "validation_failed", "Correct the invalid request fields.");
    var errors = new LinkedHashMap<String, String>();
    exception
        .getBindingResult()
        .getFieldErrors()
        .forEach(e -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
    result.setProperty("errors", errors);
    return result;
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class})
  ProblemDetail malformed(Exception exception) {
    return problem(
        400,
        "invalid_request",
        "Provide a valid JSON request using the documented fields and enum values.");
  }

  @ExceptionHandler({
    ObjectOptimisticLockingFailureException.class,
    DataIntegrityViolationException.class
  })
  ProblemDetail conflict(Exception exception) {
    return problem(
        409,
        "concurrent_change",
        "A concurrent change conflicts with this request. Refresh the record and retry.");
  }

  private ProblemDetail problem(int status, String code, String detail) {
    ProblemDetail result = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
    result.setType(URI.create("urn:relay:error:" + code));
    result.setProperty("code", code);
    result.setProperty("timestamp", Instant.now());
    result.setProperty("requestId", MDC.get("requestId"));
    return result;
  }
}
