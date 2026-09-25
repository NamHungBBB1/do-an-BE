package com.doan.game.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Vỏ bọc chung cho MỌI response. FE chỉ phải học một hình dạng duy nhất.
 *
 * Lấy ý từ BE của SWP, bỏ đi giá trị mặc định 1010 khó hiểu bên đó:
 * thành công thì code = 0, lỗi thì code là mã trong ErrorCode.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T result) {

    public static <T> ApiResponse<T> ok(T result) {
        return new ApiResponse<>(0, "OK", result);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "OK", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
