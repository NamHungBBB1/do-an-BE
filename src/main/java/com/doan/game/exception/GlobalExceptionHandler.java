package com.doan.game.shared.error;

import com.doan.game.shared.web.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    /** Lưới cuối. Log full stack trace, nhưng KHÔNG trả chi tiết ra ngoài. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleRest(Exception ex) {
        log.error("Lỗi chưa bắt", ex);
        ErrorCode ec = ErrorCode.UNCATEGORIZED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }
}
