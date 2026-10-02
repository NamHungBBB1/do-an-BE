package com.doan.game.service;

import com.doan.game.DTO.response.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Phát token. Một lớp, KHÔNG interface — chỉ có một hiện thực và sẽ không có cái thứ hai.
 *
 * Token mang đúng những gì cần để chặn cửa, không mang dữ liệu game:
 *   sub   — id tài khoản (UUID), hoặc id slot nếu là trẻ
 *   typ   — ACCOUNT | SLOT, để phân biệt tiền tố token người lớn với token trẻ
 *   scope — ADMIN / PARENT / TEACHER / CHILD, cách nhau bằng dấu cách
 *
 * Scope trong token CHỈ chặn cửa sớm. Thao tác cần gói vẫn phải gọi lại
 * EntitlementService.activePlans mỗi lần, vì gói có thể hết hạn giữa phiên.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final long adultTtlHours;
    private final long childTtlHours;

    public TokenService(JwtEncoder encoder, Clock clock,
                        @Value("${app.jwt.adult-ttl-hours:168}") long adultTtlHours,
                        @Value("${app.jwt.child-ttl-hours:8}") long childTtlHours) {
        this.encoder = encoder;
        this.clock = clock;
        this.adultTtlHours = adultTtlHours;
        this.childTtlHours = childTtlHours;
    }

    /** Token người lớn: subject là id tài khoản — mọi controller đọc "tôi là ai" theo cách này. */
    public TokenResponse choNguoiLon(UUID accountId, Set<String> scopes) {
        return phat(accountId.toString(), "ACCOUNT", scopes, adultTtlHours);
    }

    /** Token trẻ: subject là id slot, KHÔNG phải id tài khoản — trẻ không có tài khoản. */
    public TokenResponse choTre(UUID slotId) {
        return phat(slotId.toString(), "SLOT", Set.of("CHILD"), childTtlHours);
    }

    private TokenResponse phat(String sub, String typ, Set<String> scopes, long soGio) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("finteen")
                .subject(sub)
                .issuedAt(now)
                .expiresAt(now.plus(soGio, ChronoUnit.HOURS))
                .claim("typ", typ)
                // Spring Security mặc định đọc đúng claim tên "scope" và thêm tiền tố "SCOPE_",
                // nên hasAuthority("SCOPE_ADMIN") trong SecurityConfig chạy luôn, không phải sửa.
                .claim("scope", String.join(" ", scopes))
                .build();
        // BẮT BUỘC khai HS256 trong header: JwtEncoderParameters không có header thì Nimbus đi tìm
        // khoá RS256 và ném "Failed to select a JWK signing key" — khoá của ta là khoá đối xứng.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        // refreshToken = null: chưa có bảng lưu nó, giai đoạn này chỉ dùng access token.
        return new TokenResponse(token, null, soGio * 3600);
    }
}