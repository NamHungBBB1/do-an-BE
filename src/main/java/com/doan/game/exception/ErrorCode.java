package com.doan.game.exception;

import org.springframework.http.HttpStatus;

/**
 * Mọi lỗi nghiệp vụ khai ở đây, một chỗ duy nhất.
 *
 * Đánh số theo nhóm để đọc log là biết hỏng mảng nào:
 *   1xxx  chung / dữ liệu vào
 *   2xxx  telemetry
 *   3xxx  xác thực / slot của trẻ
 *   4xxx  thanh toán và gói
 *   5xxx  nhóm học
 * Thêm nhóm mới thì cấp dải mới, ĐỪNG chen số vào giữa dải cũ —
 * FE có thể đã bắt theo mã.
 */
public enum ErrorCode {

    UNCATEGORIZED(1000, "Lỗi chưa phân loại", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_FAILED(1001, "Dữ liệu gửi lên không hợp lệ", HttpStatus.BAD_REQUEST),
    MALFORMED_BODY(1002, "Body không đọc được", HttpStatus.BAD_REQUEST),
    NOT_FOUND(1003, "Không tìm thấy", HttpStatus.NOT_FOUND),
    NOT_IMPLEMENTED(1004, "Chức năng này chưa được cài đặt", HttpStatus.NOT_IMPLEMENTED),

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

    EMAIL_NOT_VERIFIED(3011, "Chưa xác thực email — nhập mã trong mail, hoặc bấm gửi lại mã",
            HttpStatus.FORBIDDEN),
    VERIFY_TOKEN_INVALID(3012, "Mã xác thực không đúng, đã dùng hoặc đã nhập sai quá 5 lần — xin mã mới", HttpStatus.BAD_REQUEST),
    VERIFY_TOKEN_EXPIRED(3013, "Mã xác thực đã hết hạn, bấm gửi lại mã", HttpStatus.GONE),
    EMAIL_ALREADY_VERIFIED(3014, "Email này đã xác thực rồi", HttpStatus.CONFLICT),
    VERIFY_TOO_SOON(3015, "Vừa gửi mail xong, đợi một phút rồi thử lại",
            HttpStatus.TOO_MANY_REQUESTS),
    ACCOUNT_LOCKED(3016, "Sai mật khẩu quá nhiều lần, thử lại sau 15 phút",
            HttpStatus.LOCKED),
    /** Tách khỏi 3012/3013: mã đặt lại mật khẩu và mã xác minh là hai việc khác nhau, gộp thì đọc sai ngữ cảnh. */
    RESET_TOKEN_INVALID(3017, "Mã đặt lại mật khẩu không đúng, đã dùng hoặc đã nhập sai quá 5 lần — xin mã mới", HttpStatus.BAD_REQUEST),
    RESET_TOKEN_EXPIRED(3018, "Mã đặt lại mật khẩu đã hết hạn, xin mã mới",
            HttpStatus.GONE),
    /** Không giới hạn số lần xin mã thì chính chỗ này thành công cụ spam mail tới người khác. */
    RESET_TOO_SOON(3019, "Vừa gửi mã đặt lại mật khẩu xong, đợi một phút rồi thử lại",
            HttpStatus.TOO_MANY_REQUESTS),

    /**
     * Google (Firebase) trả email mà đã có tài khoản đăng ký bằng mật khẩu. KHÔNG tự gộp:
     * người ta có thể chưa từng biết Google, gộp là nuốt luôn mật khẩu họ đang dùng.
     */
    GOOGLE_EMAIL_EXISTS(3020, "Email này đã có tài khoản — đăng nhập bằng mật khẩu rồi liên kết Google trong Tài khoản",
            HttpStatus.CONFLICT),
    GOOGLE_ALREADY_LINKED(3021, "Google này đã liên kết với một tài khoản khác", HttpStatus.CONFLICT),
    // 4xxx  thanh toán và gói
    PLAN_KIND_INVALID(4001, "Loại gói không hợp lệ — chỉ PARENT hoặc TEACHER", HttpStatus.BAD_REQUEST),
    PLAN_PRICE_NOT_SET(4002, "Admin chưa đặt giá cho gói này", HttpStatus.CONFLICT),
    PAYOS_NOT_CONFIGURED(4003, "Chưa cấu hình khoá PayOS trên máy chủ", HttpStatus.SERVICE_UNAVAILABLE),
    PAYOS_ERROR(4004, "Cổng thanh toán báo lỗi", HttpStatus.BAD_GATEWAY),
    TRANSACTION_NOT_FOUND(4005, "Không tìm thấy giao dịch", HttpStatus.NOT_FOUND),
    TRANSACTION_NOT_PENDING(4006, "Giao dịch không còn ở trạng thái chờ", HttpStatus.CONFLICT),
    PLAN_PRICE_INVALID(4007, "Giá gói phải là số dương (VND)", HttpStatus.BAD_REQUEST),
    // 5xxx  nhóm học
    GROUP_ALREADY_OPEN(5001, "Đã có nhóm đang mở cho loại này — đóng nhóm cũ trước khi mở nhóm mới",
            HttpStatus.CONFLICT),
    GROUP_CLOSED(5002, "Nhóm đã kết thúc, không mở thêm slot", HttpStatus.CONFLICT),
    GROUP_CONSENT_REQUIRED(5003, "Lớp học cần xác nhận đã có đồng ý của phụ huynh trước khi mở slot",
            HttpStatus.CONFLICT),
    /** Trả / xoá / đổi PIN chỉ làm được trên slot ACTIVE — ARCHIVED và WIPED đã kết thúc vòng đời. */
    SLOT_NOT_ACTIVE(5004, "Slot không còn đang mở — việc này chỉ làm được khi slot còn ACTIVE",
            HttpStatus.CONFLICT);

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
