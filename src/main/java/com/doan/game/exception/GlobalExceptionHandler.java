package com.doan.game.exception;

import com.doan.game.DTO.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Một cửa ra duy nhất cho mọi lỗi. Controller KHÔNG được try/catch rồi tự dựng response —
 * làm vậy là có hai hình dạng lỗi và FE phải học cả hai.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleApp(AppException ex) {
        ErrorCode ec = ex.getErrorCode();
        log.warn("AppException {} — {}", ec.getCode(), ex.getMessage());
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ex.getMessage()));
    }

    /** Lỗi @Valid: gom hết tên trường + lý do, đừng chỉ trả về trường đầu tiên. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), detail));
    }

    /** Lỗi validate phần tử trong List — đường này khác MethodArgumentNotValid. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        ErrorCode ec = ErrorCode.MALFORMED_BODY;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    /**
     * Thiếu tham số bắt buộc (vd POST /verify/resend thiếu ?email=) là lỗi người dùng, không phải lỗi máy:
     * rơi xuống lưới cuối thì thành 500 "Lỗi chưa phân loại" và FE không biết phải hỏi lại gì.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), "thiếu tham số " + ex.getParameterName()));
    }

    /**
     * Đường dẫn không tồn tại. Spring 6 ném NoResourceFoundException, mà nếu rơi vào lưới cuối
     * Exception thì trả 500 — gọi nhầm /api/auth/me thành "lỗi máy" thay vì "không có endpoint này".
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        ErrorCode ec = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), "không tìm thấy " + ex.getResourcePath()));
    }

    /**
     * Bốn lỗi "người gọi sai" từng rơi xuống lưới cuối thành 500/1000 (N-04, 10/10): sai method (link cũ,
     * L-24), body không phải JSON, tham số / path sai kiểu (UUID hỏng, ?status=abc), và endpoint còn khung.
     * 500 là "lỗi máy chủ" — FE báo lỗi hệ thống và ops đi tìm bug không có.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        ErrorCode ec = ErrorCode.METHOD_NOT_ALLOWED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage() + " — " + ex.getMethod()));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException ex) {
        ErrorCode ec = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), "tham số " + ex.getName() + " sai kiểu"));
    }

    /** Endpoint còn khung ném UnsupportedOperationException → 501/1004 đúng như README hứa, không phải 500. */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotImplemented(UnsupportedOperationException ex) {
        ErrorCode ec = ErrorCode.NOT_IMPLEMENTED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    /** Lưới cuối. Log full stack trace, nhưng KHÔNG trả chi tiết ra ngoài. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleRest(Exception ex) {
        log.error("Lỗi chưa bắt", ex);
        ErrorCode ec = ErrorCode.UNCATEGORIZED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }
}
