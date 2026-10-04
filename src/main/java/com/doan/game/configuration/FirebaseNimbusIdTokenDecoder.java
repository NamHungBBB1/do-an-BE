package com.doan.game.configuration;

import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Bean RIÊNG cho token Firebase. TUYỆT ĐỐI không đặt tên hay kiểu là {@code JwtDecoder} vì
 * SecurityConfig đang dùng đúng bean đó để kiểm JWT của FinTeen — nhầm là đăng nhập sập hết.
 *
 * Không cần service account, không cần thư viện mới: Firebase ID token là JWT RS256 ký bằng
 * khoá công khai của Google, Nimbus (kèm spring-security-oauth2-resource-server) tự tải khoá
 * từ JWK set của Google. BE không giữ bí mật nào.
 *
 * Kiểm: chữ ký + exp/iat + issuer theo project + aud chứa project id + phiên đăng nhập phải là
 * google.com. Phần email/email_verified để service kiểm lần nữa (đỡ tin bean phụ).
 */
@Component
public class FirebaseNimbusIdTokenDecoder implements FirebaseIdTokenDecoder {

    private static final Logger log = LoggerFactory.getLogger(FirebaseNimbusIdTokenDecoder.class);

    private static final String JWK_SET_URI =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private final String projectId;
    /** null khi chưa cấu hình — trả 501 thay vì để 500 lúc thử nút Google trước khi có project. */
    private final NimbusJwtDecoder delegate;

    public FirebaseNimbusIdTokenDecoder(@Value("${app.firebase.project-id:}") String projectId) {
        this.projectId = projectId == null ? "" : projectId.trim();
        if (this.projectId.isEmpty()) {
            this.delegate = null;
            return;
        }
        NimbusJwtDecoder d = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                // createDefaultWithIssuer đã kèm kiểm chữ ký + hạn dùng (exp/iat).
                JwtValidators.createDefaultWithIssuer("https://securetoken.google.com/" + this.projectId),
                new JwtClaimValidator<List<String>>("aud",
                        aud -> aud != null && aud.contains(this.projectId))));
        this.delegate = d;
    }

    @Override
    public NguoiFirebase decode(String idToken) {
        if (delegate == null) {
            throw new AppException(ErrorCode.NOT_IMPLEMENTED, "chưa đặt FIREBASE_PROJECT_ID");
        }
        if (idToken == null || idToken.isBlank()) {
            throw new AppException(ErrorCode.VALIDATION_FAILED, "thiếu idToken");
        }

        Jwt jwt;
        try {
            jwt = delegate.decode(idToken);
        } catch (JwtException ex) {
            // Bao gồm cả lỗi tải khoá của Google (mất mạng): không được lọt xuống lưới cuối thành
            // 500 — người dùng đang bấm nút đăng nhập, việc của BE là nói "token không đúng".
            log.warn("ID token Firebase bị từ chối: {}", ex.getMessage());
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token của Firebase không hợp lệ");
        }

        if (!"google.com".equals(nhaCungCap(jwt))) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "phiên đăng nhập không phải Google");
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token thiếu subject");
        }
        return new NguoiFirebase(sub, chuoi(jwt.getClaim("email")), chuoi(jwt.getClaim("name")),
                coXacMinhEmail(jwt));
    }

    private static String nhaCungCap(Jwt jwt) {
        Object fb = jwt.getClaim("firebase");
        if (fb instanceof Map<?, ?> m) {
            Object p = m.get("sign_in_provider");
            return p == null ? null : p.toString();
        }
        return null;
    }

    private static boolean coXacMinhEmail(Jwt jwt) {
        Object v = jwt.getClaim("email_verified");
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** Claim có thể là chuỗi hoặc kiểu khác — ép về String ở đây để service không hứng CCE (500). */
    private static String chuoi(Object giaTri) {
        return giaTri == null ? null : giaTri.toString();
    }
}
