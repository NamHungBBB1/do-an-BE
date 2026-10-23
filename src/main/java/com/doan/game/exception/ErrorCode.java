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
 *   6xxx  nội dung game (Studio)
 *   7xxx  chơi game (lưu tiến độ)
 * Thêm nhóm mới thì cấp dải mới, ĐỪNG chen số vào giữa dải cũ —
 * FE có thể đã bắt theo mã.
 */
public enum ErrorCode {

    UNCATEGORIZED(1000, "Lỗi chưa phân loại", HttpStatus.INTERNAL_SERVER_ERROR),
    VALIDATION_FAILED(1001, "Dữ liệu gửi lên không hợp lệ", HttpStatus.BAD_REQUEST),
    MALFORMED_BODY(1002, "Body không đọc được", HttpStatus.BAD_REQUEST),
    NOT_FOUND(1003, "Không tìm thấy", HttpStatus.NOT_FOUND),
    NOT_IMPLEMENTED(1004, "Chức năng này chưa được cài đặt", HttpStatus.NOT_IMPLEMENTED),
    /** Filter giới hạn theo IP (IpRateLimitFilter) ném cho các cửa công khai có gửi mail — rà soát 09/10. */
    TOO_MANY_REQUESTS(1005, "Thao tác quá nhiều lần, thử lại sau", HttpStatus.TOO_MANY_REQUESTS),
    METHOD_NOT_ALLOWED(1006, "Phương thức HTTP không được hỗ trợ ở đường dẫn này", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(1007, "Body phải là application/json", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

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
    // 23/10 cửa đăng nhập nội bộ (Hưng chốt: tài khoản nội bộ là riêng, không phải khách hàng).
    NOT_INTERNAL_ACCOUNT(3022, "Tài khoản này không phải tài khoản nội bộ", HttpStatus.FORBIDDEN),
    INTERNAL_LOGIN_REQUIRED(3023, "Tài khoản nội bộ đăng nhập ở trang đăng nhập nội bộ", HttpStatus.FORBIDDEN),
    CUSTOMER_ACCOUNT_NOT_INTERNAL(3024, "Tài khoản này đã dùng như khách hàng (gói, giao dịch hoặc nhóm) — cấp vai nội bộ"
            + " cho một tài khoản riêng", HttpStatus.CONFLICT),
    INTERNAL_ACCOUNT_NOT_CUSTOMER(3025, "Tài khoản nội bộ không mua gói, không nhận gói và không mở nhóm",
            HttpStatus.FORBIDDEN),
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
            HttpStatus.CONFLICT),

    // 6xxx — nội dung game (Studio): bản phát hành đóng băng theo phiên bản, không ghi đè.
    RELEASE_VERSION_TAKEN(6001, "Phiên bản này đã phát hành rồi — tăng số phiên bản", HttpStatus.CONFLICT),
    // 21/10 PR B: duyệt hai người (NIST AC-5) — người sửa nháp không tự duyệt, kể cả ADMIN.
    SELF_REVIEW_FORBIDDEN(6002, "Không được duyệt bản nháp do chính mình sửa", HttpStatus.FORBIDDEN),
    DRAFT_STATE_CONFLICT(6003, "Bản nháp đang ở trạng thái không cho thao tác này", HttpStatus.CONFLICT),
    // 23/10 PR C: số hiệu do BE tính từ loại thay đổi cao nhất (quy tắc 05/10 mục 3); client gửi thì phải khớp.
    RELEASE_VERSION_MISMATCH(6004, "Số phiên bản không khớp loại thay đổi của các nháp đã duyệt", HttpStatus.CONFLICT),
    NO_APPROVED_DRAFT(6005, "Không có bản nháp nào đã duyệt để phát hành", HttpStatus.CONFLICT),
    // Dùng chung như 6003 bên nháp: thu hồi bản không phải bản đang chạy, không còn bản để lùi, đặt lại bản đã thu hồi,
    // phát hành khi bản đang chạy không phải bản mới nhất.
    RELEASE_STATE_CONFLICT(6006, "Bản phát hành đang ở trạng thái không cho thao tác này", HttpStatus.CONFLICT),
    // Bốn mắt cả ở khâu phát hành (quy tắc 05/10 mục 1): người sửa nháp không tự phát hành nháp đó.
    SELF_PUBLISH_FORBIDDEN(6007, "Không được phát hành bản nháp do chính mình sửa", HttpStatus.FORBIDDEN),
    // 24/10 kho ảnh đợt 2a (spec build/spec-kho-anh-giong-2026-10-24.md mục 3).
    ASSET_INVALID(6008, "Tệp không hợp lệ — chỉ nhận ảnh PNG, JPEG, WebP tối đa 5 MB", HttpStatus.BAD_REQUEST),
    ASSET_IN_USE(6009, "Tệp đang được nội dung dùng — chỉ xoá được tệp mồ côi", HttpStatus.CONFLICT),
    ASSET_CONVERTER_UNAVAILABLE(6010, "Máy chủ chưa đổi được ảnh sang WebP (thiếu cwebp)", HttpStatus.SERVICE_UNAVAILABLE),

    // 7xxx — chơi game, lưu tiến độ (Hưng chốt 12/10).
    RELEASE_CHAPTER_NOT_FOUND(7001, "Bản phát hành không có chương này", HttpStatus.NOT_FOUND),
    SCENE_ITEM_NOT_IN_CHAPTER(7002, "Lô trỏ vào cảnh / lựa chọn / mini-game không có trong chương của bản này",
            HttpStatus.BAD_REQUEST),
    CHAPTER_NOT_UNLOCKED(7003, "Chưa tới lượt chương này hoặc attemptIndex không khớp",
            HttpStatus.CONFLICT),

    // 8xxx — quiz hai kiểu (quiz.md v2, Hưng chốt 16/10).
    QUIZ_NOT_FOUND(8001, "Không có đề này, hoặc đề chưa phát hành / không thuộc lớp của bạn",
            HttpStatus.NOT_FOUND),
    QUIZ_IMMUTABLE(8002, "Đề đã phát hành hoặc là đề hậu trắc — không sửa, xoá hay phát lại được",
            HttpStatus.CONFLICT),
    QUIZ_NOT_SUBMITTED(8003, "Em này chưa nộp bài", HttpStatus.NOT_FOUND),
    QUIZ_DUE_PASSED(8004, "Đã quá hạn nộp", HttpStatus.GONE),
    QUIZ_ALREADY_SUBMITTED(8005, "Đã nộp bài rồi — mỗi em chỉ nộp một lần",
            HttpStatus.CONFLICT),
    QUIZ_ANSWER_MISMATCH(8006, "Câu trả lời không khớp đề (câu lạ, trùng hoặc sai lựa chọn)",
            HttpStatus.BAD_REQUEST),
    QUESTION_INVALID(8007, "Câu hỏi không có trong kho, đã rút, hoặc là câu hậu trắc",
            HttpStatus.BAD_REQUEST),
    GROUP_NOT_CLASS_OR_CLOSED(8008, "Chỉ lớp đang mở mới có quiz / hậu trắc", HttpStatus.CONFLICT),
    QUESTION_IN_USE(8009, "Câu đã nằm trong đề đã phát hành — không sửa nội dung được, hãy rút rồi tạo câu mới thay thế",
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
