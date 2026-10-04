package com.doan.game.security;

import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hai nhánh PHẢI ra code rõ ràng thay vì lọt lưới cuối thành 500: thiếu cấu hình project, và
 * token rác. Cả hai đều KHÔNG gọi mạng — token không phải JWT chết ngay lúc phân tích, chưa tới
 * bước tải khoá của Google. Chữ ký thật, hạn dùng, aud/iss kiểm bằng token thật ở môi trường FE.
 */
class FirebaseIdTokenDecoderTest {

    @Test
    void missingProjectIdReturns501Not500() {
        FirebaseNimbusIdTokenDecoder decoder = new FirebaseNimbusIdTokenDecoder("");
        assertThat(errorCodeOf(decoder, "x")).isEqualTo(ErrorCode.NOT_IMPLEMENTED);
    }

    @Test
    void nonJwtTokenReturns3002() {
        FirebaseNimbusIdTokenDecoder decoder = new FirebaseNimbusIdTokenDecoder("finteen-fa26");
        assertThat(errorCodeOf(decoder, "khong-phai-jwt")).isEqualTo(ErrorCode.BAD_CREDENTIALS);
    }

    @Test
    void missingIdTokenReturns1001() {
        FirebaseNimbusIdTokenDecoder decoder = new FirebaseNimbusIdTokenDecoder("finteen-fa26");
        assertThat(errorCodeOf(decoder, "   ")).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private static ErrorCode errorCodeOf(FirebaseNimbusIdTokenDecoder decoder, String idToken) {
        try {
            decoder.decode(idToken);
            return null;
        } catch (AppException ex) {
            return ex.getErrorCode();
        }
    }
}
