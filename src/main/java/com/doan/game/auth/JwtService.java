package com.doan.game.auth;

import com.doan.game.auth.domain.Account;
import com.doan.game.auth.domain.ChildSlot;
import com.doan.game.auth.domain.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Phát token. Một lớp, không interface — chỉ có một hiện thực và sẽ không có cái thứ hai.
 *
 * Token mang đúng những gì cần để chặn cửa, không mang dữ liệu game:
 *   sub  — id tài khoản, hoặc id slot nếu là trẻ vào bằng QR
 *   typ  — "adult" | "child"
 *   scope— PARENT / TEACHER / GUEST / CHILD, cách nhau bằng dấu cách
 *   ctx  — FAMILY | CLASS, chỉ có ở token trẻ
 *
 * ctx nằm TRONG token là cố ý: ranh giới nhà/lớp phải chặn được ngay ở cổng vào,
 * không phụ thuộc client gửi lên tham số nào.
 */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final Duration adultTtl;
    private final Duration childTtl;

    public JwtService(JwtEncoder encoder,
                      @Value("${app.jwt.adult-ttl-hours:168}") long adultTtlHours,
                      @Value("${app.jwt.child-ttl-hours:8}") long childTtlHours) {
        this.encoder = encoder;
        this.adultTtl = Duration.ofHours(adultTtlHours);
        this.childTtl = Duration.ofHours(childTtlHours);
    }

    public Issued forAdult(Account a) {
        String scope = a.roles().stream().map(Role::name).collect(Collectors.joining(" "));
        return sign(a.getId().toString(), "adult", scope, null, adultTtl);
    }

    /** Token gắn CHẶT vào một slot: trẻ có cả nhà lẫn lớp thì mỗi bối cảnh một token riêng. */
    public Issued forChild(ChildSlot s) {
        return sign(s.getId().toString(), "child", "CHILD", s.getContext().name(), childTtl);
    }

    private Issued sign(String sub, String typ, String scope, String ctx, Duration ttl) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        JwtClaimsSet.Builder c = JwtClaimsSet.builder()
                .issuer("doan-game")
                .issuedAt(now)
                .expiresAt(exp)
                .subject(sub)
                .claim("typ", typ)
                .claim("scope", scope);
        if (ctx != null) c.claim("ctx", ctx);
        // Phải khai HS256 vào header: JwtEncoderParameters không có header thì Nimbus mặc định
        // đi tìm khoá RS256 và ném "Failed to select a JWK signing key" — khoá của ta là đối xứng.
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, c.build())).getTokenValue();
        return new Issued(token, ttl.toSeconds());
    }

    public record Issued(String token, long expiresIn) {}
}
