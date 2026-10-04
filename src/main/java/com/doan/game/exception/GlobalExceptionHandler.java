package com.doan.game.exception;

import com.doan.game.DTO.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.doan.game.web.HtmlPages;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
    public ResponseEntity<?> handleApp(AppException ex, HandlerMethod handler) {
        ErrorCode ec = ex.getErrorCode();
        log.warn("AppException {} — {}", ec.getCode(), ex.getMessage());
        if (isHtmlEndpoint(handler)) {
            return htmlError(ec, ex.getMessage());
        }
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ex.getMessage()));
    }

    /**
     * Link mở từ hộp thư (xác minh email, form đặt lại mật khẩu) khai báo produces text/html. Lỗi ở
     * đó phải ra TRANG HTML với đúng mã lỗi — JSON thô trên trình duyệt người dùng không đọc được.
     * Nhờ chỗ này controller không cần try/catch nào.
     */
    private static boolean isHtmlEndpoint(HandlerMethod handler) {
        // Đọc produces từ chính annotation của method ném lỗi. Không dùng thuộc tính request
        // PRODUCIBLE_MEDIA_TYPES: Spring xoá nó trước khi gọi @ExceptionHandler (test bắt được).
        if (handler == null) {
            return false;
        }
        RequestMapping m = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequestMapping.class);
        return m != null && Arrays.stream(m.produces()).anyMatch(p -> p.startsWith(MediaType.TEXT_HTML_VALUE));
    }

    private static ResponseEntity<String> htmlError(ErrorCode ec, String message) {
        return ResponseEntity.status(ec.getStatus())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(HtmlPages.error(message));
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
     * Thiếu tham số bắt buộc (vd GET /verify thiếu ?token=) là lỗi người dùng, không phải lỗi máy:
     * rơi xuống lưới cuối thì thành 500 "Lỗi chưa phân loại" và FE không biết phải hỏi lại gì.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<?> handleMissingParam(MissingServletRequestParameterException ex,
                                                HandlerMethod handler) {
        ErrorCode ec = ErrorCode.VALIDATION_FAILED;
        if (isHtmlEndpoint(handler)) {
            return htmlError(ec, "Link thiếu tham số " + ex.getParameterName() + ".");
        }
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

    /** Lưới cuối. Log full stack trace, nhưng KHÔNG trả chi tiết ra ngoài. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleRest(Exception ex) {
        log.error("Lỗi chưa bắt", ex);
        ErrorCode ec = ErrorCode.UNCATEGORIZED;
        return ResponseEntity.status(ec.getStatus())
                .body(ApiResponse.error(ec.getCode(), ec.getMessage()));
    }
}
