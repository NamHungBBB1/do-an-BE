package com.doan.game.configuration;

import com.doan.game.exception.ErrorCode;
import com.doan.game.DTO.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Chặn cửa. Cả tệp này là danh sách "ai vào được đâu" — đọc một lượt là biết hết.
 *
 * Stateless hoàn toàn: không session, không cookie, chỉ Bearer token.
 * CSRF tắt là ĐÚNG ở đây, vì không có cookie nào để trình duyệt tự gửi kèm.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/health", "/swagger/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                // Cửa vào phải mở, còn lại phải có token.
                // /verify là link người dùng bấm từ hộp thư — không thể có token ở đó.
                // Bảo vệ của nó là bản thân token ngẫu nhiên 32 byte trên URL, dùng một lần.
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/child/login",
                        "/api/auth/verify").permitAll()
                // Telemetry là sổ ghi ẩn danh của client, chốt D3 bảo game phải chạy được
                // cả khi server chết — bắt đăng nhập ở đây là đi ngược chốt đó.
                .requestMatchers("/api/telemetry/**").permitAll()
                // Phát và thu slot là việc của người lớn ĐÃ MUA GÓI. Vai nằm trong token.
                .requestMatchers("/api/slots/**").hasAnyAuthority("SCOPE_PARENT", "SCOPE_TEACHER")
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
            // Không có/sai token và thiếu quyền phải ra CÙNG một vỏ ApiResponse như mọi lỗi khác.
            // Mặc định của Spring Security trả vỏ khác, FE sẽ phải học hai hình dạng lỗi.
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> write(res, mapper, ErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((req, res, ex) -> write(res, mapper, ErrorCode.FORBIDDEN)));
        return http.build();
    }

    private static void write(jakarta.servlet.http.HttpServletResponse res, ObjectMapper mapper,
                              ErrorCode ec) throws java.io.IOException {
        res.setStatus(ec.getStatus().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(res.getWriter(), ApiResponse.error(ec.getCode(), ec.getMessage()));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Khoá đối xứng HS256. Đủ cho một hệ chỉ có MỘT dịch vụ tự phát tự kiểm token —
     * cặp khoá bất đối xứng chỉ đáng khi có dịch vụ thứ hai cần kiểm mà không được phát.
     *
     * Phải dài tối thiểu 32 ký tự (256 bit), nếu không Nimbus từ chối ngay lúc khởi động.
     * Trên máy dev dùng mặc định; deploy thì ĐẶT BIẾN MÔI TRƯỜNG JWT_SECRET.
     */
    private SecretKeySpec key;

    public SecurityConfig(@Value("${app.jwt.secret}") String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret phải dài tối thiểu 32 ký tự, đang có " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
