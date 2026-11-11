package com.ledgerflow.common.error;

import java.time.OffsetDateTime;
import java.util.List;

import com.ledgerflow.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e, HttpServletRequest request) {
        return respond(e.status(), e.code(), e.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, details);
    }

    /**
     * Constraint annotations on query and path parameters (limit, type,
     * direction). Without this they fall through to the catch-all below
     * and a typo in a URL is reported as a server error.
     */
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<ErrorResponse> handleParameterValidation(Exception e, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = List.of();
        if (e instanceof ConstraintViolationException violations) {
            details = violations.getConstraintViolations().stream()
                    .map(v -> new ErrorResponse.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                    .toList();
        } else if (e instanceof HandlerMethodValidationException invalid) {
            details = invalid.getParameterValidationResults().stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> new ErrorResponse.FieldError(
                                    result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                    .toList();
        }
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request, details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Parameter '" + e.getName() + "' has an invalid value", request, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e,
                                                         HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Parameter '" + e.getParameterName() + "' is required", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                         HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This endpoint does not support " + e.getMethod(), request, List.of());
    }

    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is malformed", request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleDenied(AccessDeniedException e, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        // Never leak internals (SQL, stack frames) to clients; log with the
        // correlation id instead so the failure is traceable.
        log.error("Unhandled exception [correlationId={}]", CorrelationIdFilter.current(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message,
                                                  HttpServletRequest request,
                                                  List<ErrorResponse.FieldError> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                OffsetDateTime.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                CorrelationIdFilter.current(),
                details));
    }
}
