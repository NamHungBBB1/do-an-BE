package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Đăng ký, xác minh email, đăng nhập, gộp cách đăng nhập, quên mật khẩu.
 *
 * Bảng phụ trách: Account, Credential, VerificationToken
 */
public interface AuthService {

    AccountResponse dangKy(RegisterRequest req);

    void xacMinhEmail(String token);

    /**
     * Gửi lại mail xác minh. Email không có tài khoản hoặc đã xác minh rồi thì im lặng —
     * trả lỗi ở nhánh "không có" là trao công cụ dò email cho người khác.
     */
    void guiLaiXacMinh(String email);

    TokenResponse dangNhap(LoginRequest req);

    /**
     * Đăng nhập/đăng ký bằng Google (qua Firebase): BE kiểm ID token rồi phát JWT FinTeen,
     * không giữ phiên nào của Google. Nhận cả req để kiểm provider chứ không chỉ idToken.
     */
    TokenResponse dangNhapGoogle(LinkCredentialRequest req);

    /**
     * Gắn cách đăng nhập Google vào tài khoản ĐANG đăng nhập (accountId lấy từ JWT FinTeen).
     * Không tự gộp lúc đăng nhập — chỉ người chứng minh được quyền sở hữu tài khoản cũ mới gộp.
     */
    void lienKetCachDangNhap(UUID accountId, LinkCredentialRequest req);

    void quenMatKhau(ForgotPasswordRequest req);

    void datLaiMatKhau(ResetPasswordRequest req);

    /**
     * "Tôi là ai": email, tên và các gói/vai ĐANG có. Sau khi đăng nhập FE gọi một lần để dựng
     * giao diện, không phải đoán vai từ payload JWT (mà JWT giờ đã bị kiểm lại với database).
     */
    AccountResponse cuaToi(UUID accountId);

    /**
     * Đổi mật khẩu khi đang đăng nhập. Trả token MỚI vì đổi mật khẩu làm mọi token cũ hết hiệu
     * lực — kể cả token của chính người vừa đổi — nếu không trả mới thì tự đá mình ra ngoài.
     */
    TokenResponse doiMatKhau(UUID accountId, DoiMatKhauRequest req);

}
