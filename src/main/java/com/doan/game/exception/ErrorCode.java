package com.doan.game.exception;

import org.springframework.http.HttpStatus;

/**
 * Mọi lỗi nghiệp vụ khai ở đây, một chỗ duy nhất.
 *
 * Đánh số theo nhóm để đọc log là biết hỏng mảng nào:
 *   1xxx  chung / dữ liệu vào
 *   2xxx  telemetry
 *   3xxx  xác thực / slot của trẻ
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
    BATCH_TOO_LARGE(2003, "Gửi quá nhiều dòng trong một lần", HttpStatus.PAYLOAD_TOO_LARGE),

    EMAIL_TAKEN(3001, "Email này đã có tài khoản", HttpStatus.CONFLICT),
    /** Dùng CHUNG cho sai email, sai mật khẩu, sai mã QR và sai PIN — đừng tách. */
    BAD_CREDENTIALS(3002, "Thông tin đăng nhập không đúng", HttpStatus.UNAUTHORIZED),
    UNAUTHENTICATED(3003, "Chưa đăng nhập hoặc token đã hết hạn", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(3004, "Không có quyền thực hiện", HttpStatus.FORBIDDEN),
    PLAN_REQUIRED(3005, "Cần mua gói phụ huynh hoặc giáo viên trước", HttpStatus.PAYMENT_REQUIRED),
    SLOT_LIMIT_REACHED(3006, "Đã hết slot — phụ huynh 4, giáo viên 40", HttpStatus.CONFLICT),
    SLOT_NOT_FOUND(3007, "Không tìm thấy slot", HttpStatus.NOT_FOUND),
    SLOT_LOCKED(3008, "Nhập sai PIN quá nhiều lần, thử lại sau 15 phút", HttpStatus.LOCKED),
    SLOT_ARCHIVED(3009, "Lớp học đã kết thúc, mã này không dùng được nữa", HttpStatus.GONE),
    SLOT_ALREADY_LINKED(3010, "Slot đã liên kết với tài khoản khác", HttpStatus.CONFLICT),

    EMAIL_NOT_VERIFIED(3011, "Chưa xác thực email — mở hộp thư và bấm link trong mail",
            HttpStatus.FORBIDDEN),
    VERIFY_TOKEN_INVALID(3012, "Link xác thực không đúng hoặc đã dùng rồi", HttpStatus.BAD_REQUEST),
    VERIFY_TOKEN_EXPIRED(3013, "Link xác thực đã hết hạn, bấm gửi lại", HttpStatus.GONE),
    EMAIL_ALREADY_VERIFIED(3014, "Email này đã xác thực rồi", HttpStatus.CONFLICT),
    VERIFY_TOO_SOON(3015, "Vừa gửi mail xong, đợi một phút rồi thử lại",
            HttpStatus.TOO_MANY_REQUESTS);

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
