package com.doan.game.configuration;

import com.doan.game.exception.ErrorCode;
import com.doan.game.DTO.response.ApiResponse;
import com.doan.game.repository.AccountRepository;
import com.doan.game.security.RevocationAwareJwtDecoder;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.service.EntitlementService;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

/**
 * Chặn cửa. Cả tệp này là danh sách "ai vào được đâu" — đọc một lượt là biết hết.
 *
 * Stateless hoàn toàn: không session, không cookie, chỉ Bearer token.
 * CSRF tắt là ĐÚNG ở đây, vì không có cookie nào để trình duyệt tự gửi kèm.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain chain(HttpSecurity http, ObjectMapper mapper,
                              JwtAuthenticationConverter jwtAuthConverter) throws Exception {
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
                // /login/google chưa chạy (thiếu OAuth client ID) nhưng CŨNG phải permitAll:
                // chặn nó ở đây thì về sau có client ID rồi vẫn 401, mà 401 là "thiếu token"
                // chứ không phải "chưa làm" — dễ hiểu nhầm thành bug.
                .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/verify",
                        "/api/auth/verify/resend", "/api/auth/login/google",
                        "/api/auth/password/forgot", "/api/auth/password/reset",
                        // Trẻ đăng nhập bằng mã + PIN nên chưa có token; webhook PayOS tự kiểm chữ ký.
                        "/api/slots/login", "/api/payments/webhook",
                        // Bảng giá công khai: trang mua gói hiện trước khi đăng nhập.
                        "/api/plans").permitAll()
                // (/api/telemetry/** từng permitAll dù không có controller — bỏ 10/10, N-19:
                // luật chết là cửa mở sẵn cho endpoint ghi DB không xác thực nếu ai đó thêm controller.)
                // Nội dung game CÔNG KHAI (Hưng chốt 08/10): kịch bản không bí mật, thứ thu tiền là
                // báo cáo và chỉ số. Chỉ GET; đường ghi nằm ở /api/studio.
                .requestMatchers(HttpMethod.GET, "/api/content/**").permitAll()
                // Việc của admin: cấp / thu vai, cấp gói không thanh toán, đăng ký URL webhook PayOS,
                // Studio giai đoạn 1 (lưu nháp + phát hành nội dung game).
                .requestMatchers("/api/admin/**", "/api/entitlements/grant", "/api/studio/**",
                        "/api/payments/webhook/confirm").hasAuthority("SCOPE_ADMIN")
                // MỞ slot mới là việc của người lớn ĐANG CÓ GÓI (vai tính lại từ gói còn hạn mỗi request).
                // Còn trả / xoá / đổi PIN slot đã có thì KHÔNG đòi gói (G-01, 10/10): hết hạn vẫn giữ
                // dữ liệu cũ và vẫn được xoá (chốt 07/10) — các đường đó rơi xuống TYP_ACCOUNT,
                // service tự kiểm chủ nhóm (3004) và gói khi mở thêm (3005).
                .requestMatchers(HttpMethod.POST, "/api/slots", "/api/slots/bulk")
                        .hasAnyAuthority("SCOPE_PARENT", "SCOPE_TEACHER")
                // Cổng của TRẺ: token typ=SLOT (sub là id slot). Kiểm theo typ chứ không theo
                // scope CHILD (G-07): một tài khoản người lớn lỡ được cấp vai CHILD cũng không
                // lọt vào đây với sub là accountId.
                .requestMatchers("/api/play/**").hasAuthority("TYP_SLOT")
                // Dashboard người học cùng cửa với /api/play — token người lớn rơi vào 403/3004 (Hưng chốt 08/10).
                .requestMatchers("/api/learner/**").hasAuthority("TYP_SLOT")
                // Mọi đường còn lại là của người lớn. Trước đây đây là `.authenticated()` —
                // mà token trẻ cũng "đã đăng nhập", nên child token gọi được cả /api/payments,
                // /api/auth/me... và controller đọc jwt.getSubject() như id tài khoản thì tra ra
                // slotId, không ra tài khoản nào. Đòi typ=ACCOUNT thì đóng được lỗ đó.
                .anyRequest().hasAuthority("TYP_ACCOUNT"))
            .oauth2ResourceServer(o -> o
                    // Không ghi dòng này thì token sai / hết hạn đi ra bằng BearerTokenEntryPoint
                    // của Spring: 401 với thân RỖNG. FE đọc body thấy chuỗi trống, không có
                    // code để bắt — đúng thứ mà exceptionHandling bên dưới định tránh.
                    .authenticationEntryPoint((req, res, ex) -> write(res, mapper, ErrorCode.UNAUTHENTICATED))
                    .jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter)))
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

    /** Khoá mặc định trong application.properties — chỉ được phép sống cùng H2 bộ nhớ (dev / test). */
    static final String DEV_SECRET = "doi-khoa-nay-truoc-khi-deploy-that-nhe-32-ky-tu";

    public SecurityConfig(@Value("${app.jwt.secret}") String secret,
                          @Value("${spring.datasource.url}") String datasourceUrl) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret phải dài tối thiểu 32 ký tự, đang có " + raw.length);
        }
        // Fail-fast (N-02, 10/10): khoá mặc định nằm trong repo public. Env production mất biến
        // JWT_SECRET (lùi .bak thiếu dòng) mà app vẫn lên thì ai cũng ký được token admin. Chết lúc
        // khởi động → health không lên → ci-deploy.sh tự lùi bản.
        if (DEV_SECRET.equals(secret) && !datasourceUrl.startsWith("jdbc:h2:mem")) {
            throw new IllegalStateException(
                    "app.jwt.secret đang là khoá mặc định của repo — đặt biến môi trường JWT_SECRET trước khi chạy với DB thật");
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    /**
     * Suy quyền từ JWT: scope → SCOPE_xxx (giống mặc định của Spring), cộng thêm typ → TYP_xxx
     * để SecurityConfig phân biệt token người lớn với token trẻ. Spring không tự thêm tiền tố
     * cho claim typ nên phải làm tay.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scope");
        scopes.setAuthorityPrefix("SCOPE_");
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> {
            Set<GrantedAuthority> authorities = new HashSet<>(scopes.convert(jwt));
            String typ = jwt.getClaimAsString("typ");
            if (typ != null && !typ.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("TYP_" + typ));
            }
            return authorities;
        });
        return c;
    }

    /**
     * Decoder có kiểm database. Cho nên bean này là RevocationAwareJwtDecoder bọc ngoài Nimbus:
     * Nimbus vẫn lo chữ ký + hạn, còn lớp bọc lo việc token có còn được phép dùng không.
     *
     * setJwtValidator là BẮT BUỘC: mặc định Nimbus kiểm hạn bằng đồng hồ hệ thống, còn ta PHÁT
     * token bằng Clock của ứng dụng. Hai cái lệch nhau là token sống hay chết không đúng với lúc
     * nó được phát — trên máy dev có Clock đóng băng thì token trẻ (8 giờ) chết ngay trong khi
     * người ta vừa đăng nhập xong.
     */
    @Bean
    JwtDecoder jwtDecoder(AccountRepository accountRepo, AccountRoleRepository accountRoleRepo,
                          EntitlementService entitlementService, Clock clock) {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        nimbus.setJwtValidator(timestampValidator);
        return new RevocationAwareJwtDecoder(nimbus, accountRepo, accountRoleRepo, entitlementService);
    }
}
