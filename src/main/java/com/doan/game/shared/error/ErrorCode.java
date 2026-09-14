package com.doan.game.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Mọi lỗi nghiệp vụ khai ở đây, một chỗ duy nhất.
 *
 * Đánh số theo nhóm để đọc log là biết hỏng mảng nào:
 *   1xxx  chung / dữ liệu vào
 *   2xxx  telemetry
 * Thêm nhóm mới thì cấp dải mới, ĐỪNG chen số vào giữa dải cũ —
 * FE có thể đã bắt theo mã.
 */
public enum ErrorCode {

    UNCATEGORIZED(1000, "Lỗi chưa phân loại", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_FAILED(1001, "Dữ liệu gửi lên không hợp lệ", HttpStatus.BAD_REQUEST),
    MALFORMED_BODY(1002, "Body không đọc được", HttpStatus.BAD_REQUEST),
    NOT_FOUND(1003, "Không tìm thấy", HttpStatus.NOT_FOUND),

    SEED_REQUIRED(2001, "Thiếu seed — không có seed thì không so sánh được giữa các lượt chơi",
            HttpStatus.BAD_REQUEST),
    BUILD_VERSION_REQUIRED(2002, "Thiếu build_version — không biết dòng log này sinh ra từ bản nào",
            HttpStatus.BAD_REQUEST),
    BATCH_TOO_LARGE(2003, "Gửi quá nhiều dòng trong một lần", HttpStatus.PAYLOAD_TOO_LARGE);

    private final int code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(int code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
    public HttpStatus getStatus() { return status; }
}
