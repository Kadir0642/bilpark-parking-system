package com.bilpark.backend.controller;

import com.bilpark.backend.audit.ErrorLogService;
import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler. Every error body is {"error": "<Turkish message>", "code": "<ErrorCodes>"}.
 * Raw SQL / internal messages never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorLogService errorLogService; // 500s are kept for the developer's recent-errors view

    public GlobalExceptionHandler(ErrorLogService errorLogService) {
        this.errorLogService = errorLogService;
    }

    static final String CONFLICT_MSG = "Kayıt çakışması: bu işlem başka bir işlemle aynı anda yapıldı veya kayıt zaten var.";
    static final String LOCK_MSG = "Araç aynı anda başka bir cihazda işleniyor, lütfen tekrar deneyin.";

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("code", code);
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApi(ApiException ex) {
        return body(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, msg.isBlank() ? "Geçersiz istek." : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraint(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(cv -> cv.getMessage())
                .collect(Collectors.joining(", "));
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, msg.isBlank() ? "Geçersiz istek." : msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, String>> handleMethodValidation(HandlerMethodValidationException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, "Geçersiz istek parametresi.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Eksik parametre: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST,
                "Geçersiz parametre değeri: " + ex.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, "İstek gövdesi okunamadı veya hatalı biçimde.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.BAD_REQUEST, "Bu istek yöntemi desteklenmiyor.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.BAD_REQUEST, "Desteklenmeyen içerik türü.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNoResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, "Kaynak bulunamadı.");
    }

    // Spring Security denials (incl. @PreAuthorize and the service's own zone checks) stay 403
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank() || "Access Denied".equalsIgnoreCase(m) || "Access is denied".equalsIgnoreCase(m)) {
            m = "Bu işlem için yetkiniz yok.";
        }
        return body(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, m);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, CONFLICT_MSG);
    }

    @ExceptionHandler({PessimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class,
            OptimisticLockException.class})
    public ResponseEntity<Map<String, String>> handleLocking(Exception ex) {
        log.warn("Locking failure: {}", ex.getClass().getSimpleName());
        return body(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, LOCK_MSG);
    }

    // Any other database error: its message may contain SQL, so it never reaches the client
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,
            jakarta.persistence.PersistenceException.class,
            org.hibernate.HibernateException.class})
    public ResponseEntity<Map<String, String>> handleDataAccess(Exception ex) {
        log.error("Database error", ex);
        errorLogService.record(ex, ErrorCodes.INTERNAL_ERROR);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "Veritabanı hatası oluştu. Lütfen tekrar deneyin.");
    }

    // Legacy business errors thrown as plain RuntimeException
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        String m = ex.getMessage();
        return body(HttpStatus.BAD_REQUEST, ErrorCodes.BAD_REQUEST, m == null || m.isBlank() ? "Geçersiz istek." : m);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        errorLogService.record(ex, ErrorCodes.INTERNAL_ERROR);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "Beklenmeyen bir hata oluştu. Lütfen tekrar deneyin.");
    }
}
