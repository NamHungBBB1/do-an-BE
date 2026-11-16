package com.doan.game.exception;

/** Ném cái này cho mọi lỗi nghiệp vụ. GlobalExceptionHandler lo phần còn lại. */
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    /** Chi tiết có cấu trúc trả trong result của lỗi (vd lỗi theo vị trí câu của lô, quiz v3); null = không có. */
    private final Object details;

    public AppException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    public AppException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    public AppException(ErrorCode errorCode, String detail, Object details) {
        super(errorCode.getMessage() + " — " + detail);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
