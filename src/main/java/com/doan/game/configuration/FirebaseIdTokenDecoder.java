package com.doan.game.configuration;

/**
 * Kiểm ID token của Firebase (RS256, khoá công khai của Google) rồi giao các claim mà service cần.
 *
 * Tách thành interface để test thay bằng bản stub: test đăng nhập Google không được gọi Google thật,
 * nhưng luồng HTTP vẫn phải chạy y hệt production.
 */
public interface FirebaseIdTokenDecoder {

    /**
     * Ném AppException nếu token sai, hết hạn, sai project, hoặc chưa cấu hình FIREBASE_PROJECT_ID.
     */
    NguoiFirebase decode(String idToken);

    /**
     * sub là uid Firebase — khoá thật, đổi email không đổi được nó. Dùng làm Credential.subject,
     * KHÔNG dùng email (email đổi được và có thể tái sử dụng).
     */
    record NguoiFirebase(String sub, String email, String ten, boolean emailDaXacMinh) {
    }
}
