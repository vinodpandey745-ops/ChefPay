package com.chefpay.server.common;

import jakarta.validation.ConstraintViolationException;
import org.hibernate.StaleObjectStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Translates every exception into the standard {@link ApiResponse} envelope (requirement §52) -
 * clients never see a raw stack trace, and version conflicts get a stable, documented error code
 * (ARCHITECTURE.md §8) rather than a generic 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Follow-up enhancement ("Enable Logging"): every business-rule rejection thrown as an {@link
     * ApiException} anywhere in the app (invalid login, a forbidden action, a version conflict, a
     * "branch has active terminals" block, etc.) used to leave ZERO trace in the application log -
     * this handler translated it straight to the response envelope with no log statement at all,
     * unlike {@link #handleLockContention}/{@link #handleUnexpected} below. That made even the
     * simplest "why did this fail?" production question (e.g. a user reporting a stuck PO approval,
     * or "no users showing" for a branch) undiagnosable from the log file alone. WARN (not ERROR) -
     * these are expected, handled outcomes, not bugs - but now searchable by {@code errorCode} and
     * {@code correlationId} alongside every other log line for the same request. A caller-facing
     * 5xx {@link ApiException} (rare - most of this codebase's own throws are 4xx) logs at ERROR
     * instead, matching {@link #handleUnexpected}'s severity for anything actually unexpected. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("API exception {} (correlationId={}, status={}): {}",
                    ex.getErrorCode(), CorrelationIdHolder.get(), ex.getStatus().value(), ex.getMessage());
        } else {
            log.warn("API exception {} (correlationId={}, status={}): {}",
                    ex.getErrorCode(), CorrelationIdHolder.get(), ex.getStatus().value(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus()).body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, StaleObjectStateException.class})
    public ResponseEntity<ApiResponse<Void>> handleVersionConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("VERSION_CONFLICT",
                        "This record was updated by another terminal. Refresh and try again."));
    }

    /**
     * Falls back here only if {@code OrderController#withLockRetry} (or any other call site with
     * its own retry) has already exhausted its retries against a persistent SQLite write-lock
     * collision - see that method's javadoc. 503 (not 500) so a client could reasonably auto-retry
     * the whole request, and a plain-language message rather than the raw "[SQLITE_BUSY] The
     * database file is locked" text this used to surface as an opaque 500.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ApiResponse<Void>> handleLockContention(CannotAcquireLockException ex) {
        log.warn("Database write-lock contention (correlationId={}): {}", CorrelationIdHolder.get(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("SERVER_BUSY", "The system is busy right now - please try again in a moment."));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        log.warn("Invalid credentials (correlationId={})", CorrelationIdHolder.get());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("INVALID_CREDENTIALS", "Invalid username/password or PIN."));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied (correlationId={}): {}", CorrelationIdHolder.get(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("FORBIDDEN", "You do not have permission to perform this action."));
    }

    /**
     * {@code ex.getMessage()} on a raw {@link MethodArgumentNotValidException} is a multi-line
     * dump of the Java method signature, every rejected field, and the full Spring validator
     * machinery behind it (harmless for logs, unreadable as an end-user error dialog - see the
     * Inventory "receive stock with a blank reason" report this was written for). Pull out just
     * the "field: message" pairs instead so clients can show something a person can act on.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> humanizeField(fe.getField()) + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Please check the values you entered and try again.";
        }
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + " " + cv.getMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Please check the values you entered and try again.";
        }
        return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", message));
    }

    /** "recordTransactionRequest.reason" -> "reason" - strip the record/param name Spring prefixes onto nested field paths. */
    private String humanizeField(String field) {
        int lastDot = field.lastIndexOf('.');
        return lastDot < 0 ? field : field.substring(lastDot + 1);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception, correlationId={}", CorrelationIdHolder.get(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "Something went wrong. Please try again."));
    }
}
