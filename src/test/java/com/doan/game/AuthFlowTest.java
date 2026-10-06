package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.security.FirebaseIdTokenDecoder;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.enums.TokenPurpose;
import com.doan.game.exception.AppException;
import com.doan.game.exception.ErrorCode;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.repository.VerificationTokenRepository;
import com.doan.game.service.OutgoingMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Luồng auth đi qua HTTP thật (MockMvc + H2). Điểm khác mọi test trước: lần đầu có token THẬT
 * để dùng — AdminPlanFlowTest phải giả JWT bằng spring-security-test vì chưa có chỗ nào phát.
 * Ca thứ hai kiểm đúng điều đó: token lấy từ /login đem sang endpoint khác, không còn 3003.
 *
 * Mail không gửi thật (máy không có MAIL_HOST nên MailService chỉ in log). Token gốc được bắt ở
 * đây bằng một listener THẬT chứ không mock MailService — thay bean bằng mock sẽ làm mất luôn
 * việc đăng ký @TransactionalEventListener, nên không bắt được sự kiện nào.
 *
 * Đồng hồ đóng băng ở 10:00 sáng 02/10/2026 giờ Việt Nam, nên hạn token và khoá 15 phút kiểm
 * được ngay thay vì phải chờ.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        // Cố ý chọn email mà có email khác là CHUỖI CON của nó: min@fifteen.com nằm trong
        // admin@fifteen.com. Ca 10 dùng đúng cái bẫy này.
        "app.admin-emails=admin@fifteen.com,gg-admin@gmail.com"})
@AutoConfigureMockMvc
class AuthFlowTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern OTP_IN_MAIL = Pattern.compile(">(\\d{6})</p>");

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired LearnerGroupRepository groupRepo;
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired VerificationTokenRepository tokenRepo;
    @Autowired MailCatcher mailCatcher;
    @Autowired FakeGoogleDecoder price;

    @BeforeEach
    void resetMailbox() {
        mailCatcher.sent.clear();
        price.user = null;
    }

    // ------------------------------------------------------------------ 1
    @Test
    void cannotLoginBeforeEmailVerified() throws Exception {
        String email = "khaxacminh-" + System.nanoTime() + "@test.local";
        register(email);

        mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3011));
    }

    // ------------------------------------------------------------------ 2
    @Test
    void verifiedUserLogsInAndTokenWorksElsewhere() throws Exception {
        String email = "hanhtinh-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());

        var res = mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.result.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.result.expiresIn").value(168 * 3600))
                .andReturn();

        String jwt = extractJwt(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 4005 = giao dịch không có trong bảng. Quan trọng là KHÔNG phải 3003: token thật đã qua
        // được chặn cửa, và subject của nó đọc ra đúng id tài khoản.
        mvc.perform(get("/api/payments/123").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.code").value(4005));
    }

    // ------------------------------------------------------------------ 3
    @Test
    void duplicateEmailWithDifferentCaseIsRejected() throws Exception {
        String email = "trung-" + System.nanoTime() + "@test.local";
        register(email);

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email.toUpperCase(Locale.ROOT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3001));
    }

    // ------------------------------------------------------------------ 4
    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        String email = "sailan-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());
        mailCatcher.sent.clear();

        for (int i = 1; i <= 5; i++) {
            mvc.perform(login(email, "saisaitinh-" + i))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(3002));
        }
        // Mật khẩu ĐÚNG ở lần 6 vẫn bị chặn. Đây là ca canh bẫy transaction: nếu login có
        // @Transactional thì 5 lần sai vừa rồi bị rollback, bộ đếm về 0 và lần này lọt.
        mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(3016));

        // Đăng nhập không bao giờ gửi mail — kể cả lần bị khoá.
        assertThat(mailCatcher.sent).isEmpty();
    }

    // ------------------------------------------------------------------ 5
    /** Mã đã dùng không dùng lại được: tài khoản đã xác minh thì báo 3014, không phát token lần hai. */
    @Test
    void verifyingTwiceReturnsAlreadyVerified() throws Exception {
        String email = "haiLan-" + System.nanoTime() + "@test.local";
        register(email);
        String otp = tokenFromMail();

        verify(otp);
        mvc.perform(verifyOtp(email, otp))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3014));
    }

    // ------------------------------------------------------------------ 6
    @Test
    void forgotPasswordForUnknownEmailReturns200WithoutMail() throws Exception {
        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"khongco-that-" + System.nanoTime() + "@test.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(mailCatcher.sent).isEmpty();
    }

    // ------------------------------------------------------------------ 7
    @Test
    void resetPasswordInvalidatesOldPassword() throws Exception {
        String email = "datlai-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());

        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"otp\":\"" + tokenFromMail()
                                + "\",\"newPassword\":\"matkhaumoi123\"}"))
                .andExpect(status().isOk());

        mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3002));
        mvc.perform(login(email, "matkhaumoi123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty());
    }

    // ------------------------------------------------------------------ 8
    @Test
    void activeParentPlanGivesParentScope() throws Exception {
        String email = "cogoi-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());

        Account a = accountRepo.findByEmailIgnoreCase(email).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(PlanKind.PARENT);
        e.setStartsOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
        e.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(FIXED_CLOCK));
        entitlementRepo.save(e);

        var res = mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn();
        String jwt = extractJwt(res.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // /api/slots/** chặn bằng SCOPE_PARENT | SCOPE_TEACHER. Không 403 tức là token đã mang scope.
        var response = mvc.perform(post("/api/slots")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Be\",\"context\":\"FAMILY\"}"))
                .andReturn();
        assertThat(response.getResponse().getStatus()).isNotEqualTo(403);
        assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("\"code\":3004");
    }

    // ------------------------------------------------------------------ 9
    @Test
    void treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa() throws Exception {
        String code = "MA" + (System.nanoTime() % 100000);
        slot(code, "123456", SlotStatus.ACTIVE);

        mvc.perform(loginSlot(code, "123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.result.expiresIn").value(8 * 3600));

        for (int i = 1; i <= 5; i++) {
            mvc.perform(loginSlot(code, "99999" + i))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(3002));
        }
        // PIN đúng vẫn vào không được. Khoá 15 phút tính từ lockedUntil, không có job mở khoá.
        mvc.perform(loginSlot(code, "123456"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(3008));
    }

    // ------------------------------------------------------------------ 10
    /**
     * ADMIN_EMAILS khớp TỪNG EMAIL, không phải {@code String.contains}.
     *
     * Với ADMIN_EMAILS=admin@fifteen.com, chuỗi "min@fifteen.com" là chuỗi con. Nếu dùng contains
     * thì chỉ cần tự đăng ký min@fifteen.com là lên được quyền admin — không cần biết mật khẩu ai.
     * Email cố ý viết cứng, không có nanoTime: cần chính xác từng ký tự để bẫy mới trúng.
     */
    @Test
    void substringOfAdminEmailDoesNotGetAdmin() throws Exception {
        String substringEmail = "min@fifteen.com";
        register(substringEmail);
        verify(tokenFromMail());

        var res = mvc.perform(login(substringEmail, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(extractScope(extractJwt(res.getResponse().getContentAsString(StandardCharsets.UTF_8))))
                .doesNotContain("ADMIN");

        // Đối chứng: chính email trong danh sách thì vẫn phải được cấp, không phải hỏng vì tôi sửa lỗi.
        register("admin@fifteen.com");
        verify(tokenFromMail());

        var res2 = mvc.perform(login("admin@fifteen.com", "matkhau123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(extractScope(extractJwt(res2.getResponse().getContentAsString(StandardCharsets.UTF_8))))
                .contains("ADMIN");
    }

    // ------------------------------------------------------------------ 11
    /**
     * Mã 6 số chỉ có 1 triệu tổ hợp: sai 5 lần thì mã chết, kể cả lần thứ 6 gõ ĐÚNG. Bộ đếm phải
     * sống qua lỗi — verifyEmail cố ý không @Transactional, nếu lỡ thêm vào thì ca này đỏ.
     */
    @Test
    void fiveWrongOtpsKillTheCode() throws Exception {
        String email = "doma-" + System.nanoTime() + "@test.local";
        register(email);
        String otp = tokenFromMail();
        String wrong = otp.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            mvc.perform(verifyOtp(email, wrong))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(3012));
        }
        mvc.perform(verifyOtp(email, otp))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3012));
    }

    /** Gửi lại mã thì mã cũ chết: lúc nào cũng chỉ một mã sống, gửi lại không nhân đôi số lần đoán. */
    @Test
    void resendKillsOldCodeAndNewCodeWorks() throws Exception {
        String email = "guilai-" + System.nanoTime() + "@test.local";
        register(email);
        String oldOtp = tokenFromMail();

        // Đồng hồ đóng băng nên chặn "gửi lại trong 1 phút" luôn bật — lùi createdAt của mã cũ.
        UUID accountId = accountRepo.findByEmailIgnoreCase(email).orElseThrow().getId();
        tokenRepo.findByAccount_IdAndPurposeAndUsedAtIsNull(accountId, TokenPurpose.VERIFY_EMAIL)
                .forEach(t -> { t.setCreatedAt(t.getCreatedAt().minusSeconds(120)); tokenRepo.save(t); });
        mvc.perform(post("/api/auth/verify/resend").param("email", email)).andExpect(status().isOk());
        String newOtp = tokenFromMail();

        if (!oldOtp.equals(newOtp)) {
            mvc.perform(verifyOtp(email, oldOtp))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(3012));
        }
        verify(newOtp);
    }

    /** Email không có tài khoản trả cùng lỗi với mã sai — không dò được email nào đã đăng ký. */
    @Test
    void unknownEmailOtpLooksLikeWrongCode() throws Exception {
        mvc.perform(verifyOtp("khongco-" + System.nanoTime() + "@test.local", "123456"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3012));
    }

    // ------------------------------------------------------------------ 12  (C1)
    /** FE cần "tôi là ai" sau khi đăng nhập: email, tên, gói và vai lấy từ database. */
    @Test
    void meReturnsEmailPlansAndRoles() throws Exception {
        String email = "toilaai-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());

        String jwt = extractJwt(mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value(email))
                .andExpect(jsonPath("$.result.displayName").value("Nguyen Test"))
                .andExpect(jsonPath("$.result.plans").isArray())
                .andExpect(jsonPath("$.result.roles").isArray());
    }

    // ------------------------------------------------------------------ 13  (C2 + C3)
    /**
     * Đổi mật khẩu phải đưa mật khẩu cũ, và làm token CŨ chết ngay — nếu không thì kẻ đang
     * giữ token bị đánh cắp vẫn đi lại được cả tuần dù chủ đã đổi mật khẩu.
     */
    @Test
    void changePasswordIssuesNewTokenAndRevokesOld() throws Exception {
        String email = "doimk-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());

        String old = extractJwt(mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        // Không có mật khẩu cũ thì token đánh cắp cũng đổi được mật khẩu -> bị từ chối.
        mvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + old)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"sai-het\",\"newPassword\":\"matkhaumoi123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3002));

        // Mật khẩu cũ đúng -> 200 và trả token MỚI, vì token cũ vừa bị chính cú này thu hồi.
        var res = mvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + old)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"matkhau123\",\"newPassword\":\"matkhaumoi123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andReturn();
        String newToken = extractJwt(res.getResponse().getContentAsString(StandardCharsets.UTF_8));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());

        // Vào được bằng mật khẩu mới, còn mật khẩu cũ thì không.
        mvc.perform(login(email, "matkhau123")).andExpect(status().isUnauthorized());
        mvc.perform(login(email, "matkhaumoi123")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 14  (C4)
    /**
     * Token trẻ (typ=SLOT, sub là id slot) không được gọi API người lớn: controller đọc
     * jwt.getSubject() như id tài khoản thì sẽ tra ra slotId và không ra tài khoản nào.
     */
    @Test
    void childTokenCannotCallAdultApi() throws Exception {
        String code = "MA" + (System.nanoTime() % 100000);
        slot(code, "123456", SlotStatus.ACTIVE);

        String childToken = extractJwt(mvc.perform(loginSlot(code, "123456"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(extractScope(childToken)).isEqualTo("CHILD");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + childToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));
        mvc.perform(get("/api/payments/1").header("Authorization", "Bearer " + childToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 15
    /**
     * Token sai / hết hạn phải ra CÙNG vỏ ApiResponse như mọi lỗi khác. Nếu không ghi
     * authenticationEntryPoint riêng thì Spring trả 401 với thân rỗng, FE không có code để bắt.
     */
    @Test
    void invalidTokenStillReturnsApiResponse() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer khong-phai-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3003));
    }

    // ------------------------------------------------------------------ 16 (Google)
    /**
     * Nút Google cho người CHƯA có tài khoản: BE kiểm ID token rồi tạo Account + Credential
     * GOOGLE và phát JWT như đăng nhập mật khẩu. Bean kiểm token được thay bằng stub nên test
     * không gọi Google thật — chữ ký thật do FirebaseIdTokenDecoderTest kiểm riêng.
     */
    @Test
    void googleLoginCreatesAccountThenReusesIt() throws Exception {
        String email = "gg-" + System.nanoTime() + "@gmail.com";
        price.user = new FirebaseIdTokenDecoder.FirebaseUser(
                "uid-" + System.nanoTime(), email, "Nguyen Test", true);

        String jwt = extractJwt(mvc.perform(loginWithGoogle("GOOGLE", "token-that"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value(email));

        // Đăng nhập lần hai bằng CÙNG uid: vào đúng tài khoản cũ, không sinh bản sao.
        long before = accountRepo.count();
        mvc.perform(loginWithGoogle("GOOGLE", "token-that")).andExpect(status().isOk());
        assertThat(accountRepo.count()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 17 (Google)
    /** Email đã có tài khoản mật khẩu → 3020, KHÔNG gộp (gộp là nuốt mất mật khẩu người ta). */
    @Test
    void googleLoginWithPasswordAccountEmailReturns3020() throws Exception {
        String email = "co-mk-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());

        price.user = new FirebaseIdTokenDecoder.FirebaseUser(
                "uid-" + System.nanoTime(), email, "Nguyen Test", true);
        mvc.perform(loginWithGoogle("GOOGLE", "token-that"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3020));
    }

    // ------------------------------------------------------------------ 18 (Google)
    /** Token không kiểm được và email Google chưa xác minh: cả hai 3002, không lộ khác biệt. */
    @Test
    void googleLoginWithBadTokenOrUnverifiedEmailReturns3002() throws Exception {
        mvc.perform(loginWithGoogle("GOOGLE", "token-sai"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3002));

        price.user = new FirebaseIdTokenDecoder.FirebaseUser(
                "uid-" + System.nanoTime(), "chua-xac-minh@gmail.com", "Ten", false);
        mvc.perform(loginWithGoogle("GOOGLE", "token-that"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3002));
    }

    // ------------------------------------------------------------------ 19 (Google)
    /**
     * Liên kết: người đang đăng nhập bằng mật khẩu gắn Google vào tài khoản của mình, rồi đăng
     * nhập bằng Google ra CÙNG tài khoản. Khóa là uid Firebase nên email Google khác vẫn vào đúng.
     */
    @Test
    void linkGoogleThenGoogleLoginReachesSameAccount() throws Exception {
        String email = "gop-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());
        String uid = "uid-gop-" + System.nanoTime();
        price.user = new FirebaseIdTokenDecoder.FirebaseUser(uid, email, "Nguyen Test", true);

        String passwordJwt = extractJwt(mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post("/api/auth/link/google")
                        .header("Authorization", "Bearer " + passwordJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleLoginJson("GOOGLE", "token-that")))
                .andExpect(status().isOk());

        // Gắn lại lần nữa: lặp vô hại, không tạo Credential thứ hai.
        mvc.perform(post("/api/auth/link/google")
                        .header("Authorization", "Bearer " + passwordJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleLoginJson("GOOGLE", "token-that")))
                .andExpect(status().isOk());

        // Cùng uid, email KHÁC: vẫn vào tài khoản cũ vì khóa là uid chứ không phải email.
        price.user = new FirebaseIdTokenDecoder.FirebaseUser(uid, "google-khac@gmail.com", "Ten khac", true);
        String googleJwt = extractJwt(mvc.perform(loginWithGoogle("GOOGLE", "token-that"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + googleJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.email").value(email));
    }

    // ------------------------------------------------------------------ 20 (L-18)
    /**
     * Bấm nút Google hai lần ở lần ĐẦU (gặp thật 04/10 với token Google thật): nhiều request cùng
     * tạo một tài khoản. Tất cả phải 200 và ra CÙNG một tài khoản — trước khi sửa, request sau ra
     * 500 (khoá bảng H2 hết giờ) hoặc 3020 (UNIQUE trên PostgreSQL).
     */
    @Test
    void concurrentFirstGoogleLoginsYieldOneAccount() throws Exception {
        String email = "gg-dup-" + System.nanoTime() + "@gmail.com";
        price.user = new FirebaseIdTokenDecoder.FirebaseUser("uid-dup-" + System.nanoTime(), email, "Bam Dup", true);
        long before = accountRepo.count();

        int threads = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch startSignal = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startSignal.await();
                var r = mvc.perform(loginWithGoogle("GOOGLE", "token-that")).andReturn().getResponse();
                return r.getStatus() + " " + r.getContentAsString(StandardCharsets.UTF_8);
            }));
        }
        startSignal.countDown();
        java.util.Set<String> currentAccountId = new java.util.HashSet<>();
        for (var f : futures) {
            String r = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r).as("moi request phai 200: " + r).startsWith("200 ");
            String jwt = extractJwt(r);
            currentAccountId.add(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8)
                    .replaceAll(".*\"sub\":\"([^\"]+)\".*", "$1"));
        }
        pool.shutdown();
        assertThat(currentAccountId).as("cung mot tai khoan").hasSize(1);
        assertThat(accountRepo.count()).isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------ 21 (L-16)
    /** Người trong ADMIN_EMAILS đăng ký bằng Google cũng được ADMIN (email đã do Google xác minh). */
    @Test
    void adminEmailSigningUpWithGoogleGetsAdmin() throws Exception {
        price.user = new FirebaseIdTokenDecoder.FirebaseUser("uid-adm-" + System.nanoTime(),
                "gg-admin@gmail.com", "Admin Google", true);
        String jwt = extractJwt(mvc.perform(loginWithGoogle("GOOGLE", "token-that"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(extractScope(jwt)).isEqualTo("ADMIN");
    }

    // ------------------------------------------------------------------ 22 (L-13)
    /**
     * Mua gói SAU khi đã đăng nhập: token cũ (scope rỗng) không bị đá ra 401 mà có quyền PARENT
     * ngay ở request kế tiếp — decoder thay scope bằng vai + gói hiện có trong DB.
     */
    @Test
    void buyingPlanAfterLoginUpdatesScopeWithoutLogout() throws Exception {
        String email = "muasau-" + System.nanoTime() + "@test.local";
        register(email);
        verify(tokenFromMail());
        String jwt = extractJwt(mvc.perform(login(email, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(extractScope(jwt)).isEmpty();

        mvc.perform(post("/api/slots").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        Account a = accountRepo.findByEmailIgnoreCase(email).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(PlanKind.PARENT);
        e.setStartsOn(LocalDate.now(FIXED_CLOCK));
        e.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(3).minusDays(1));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(FIXED_CLOCK));
        entitlementRepo.save(e);

        // Cùng token cũ: vẫn đăng nhập (không 401), và giờ qua được cửa PARENT (không 403).
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.plans[0]").value("PARENT"));
        var response = mvc.perform(post("/api/slots").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn();
        assertThat(response.getResponse().getStatus()).isNotIn(401, 403);
    }

    // ------------------------------------------------------------------ 23 (token thật)
    /**
     * Token THẬT lấy từ /api/auth/verify (đăng ký → OTP → token) rồi gọi GET /api/entitlements.
     * Khác với EntitlementFlowTest (jwt giả): ca này đi qua RevocationAwareJwtDecoder thật, nên
     * cửa TYP_ACCOUNT và cách decoder đọc subject được kiểm trên đúng token BE tự cấp.
     */
    @Test
    void realTokenListsOwnEntitlements() throws Exception {
        String email = "goicu-" + System.nanoTime() + "@test.local";
        register(email);
        String jwt = extractJwt(mvc.perform(verifyOtp(email, tokenFromMail()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        Account a = accountRepo.findByEmailIgnoreCase(email).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(PlanKind.PARENT);
        e.setStartsOn(LocalDate.now(FIXED_CLOCK).minusDays(1));
        e.setExpiresOn(LocalDate.now(FIXED_CLOCK).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(FIXED_CLOCK));
        entitlementRepo.save(e);

        mvc.perform(get("/api/entitlements").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(1))
                .andExpect(jsonPath("$.result[0].kind").value("PARENT"));
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    /** Xác minh bằng mã OTP của lá mail mới nhất, gửi tới đúng email người nhận lá đó. */
    private void verify(String otp) throws Exception {
        mvc.perform(verifyOtp(lastMail().to(), otp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty());
    }

    private static MockHttpServletRequestBuilder verifyOtp(String email, String otp) {
        return post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"otp\":\"" + otp + "\"}");
    }

    private LearnerSlot slot(String code, String pin, SlotStatus status) {
        Account owner = new Account();
        owner.setEmail("slot-chu-" + System.nanoTime() + "@test.local");
        owner.setDisplayName("Chu slot");
        owner.setCreatedAt(Instant.now(FIXED_CLOCK));
        owner = accountRepo.save(owner);

        LearnerGroup g = new LearnerGroup();
        g.setOwner(owner);
        g.setContext(LearningContext.FAMILY);
        g.setOpenContext(LearningContext.FAMILY);
        g.setName("Nha test");
        g.setSlotLimit(4);
        g.setOpenedAt(Instant.now(FIXED_CLOCK));
        g = groupRepo.save(g);

        LearnerSlot s = new LearnerSlot();
        s.setGroup(g);
        s.setCode(code);
        s.setPinHash(encoder.encode(pin));
        s.setDisplayName("Be");
        s.setStatus(status);
        s.setFailedAttempts(0);
        s.setCreatedAt(Instant.now(FIXED_CLOCK));
        return slotRepo.save(s);
    }

    private static String registerJson(String email) {
        return "{\"email\":\"" + email + "\",\"phone\":\"0900000000\","
                + "\"password\":\"matkhau123\",\"displayName\":\"Nguyen Test\"}";
    }

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static MockHttpServletRequestBuilder loginSlot(String code, String pin) {
        return post("/api/slots/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"pin\":\"" + pin + "\"}");
    }

    /** Body mà FE gửi cho nút Google (xem context Firebase 04/10, mục 5). */
    private static String googleLoginJson(String provider, String idToken) {
        return "{\"provider\":\"" + provider + "\",\"idToken\":\"" + idToken + "\"}";
    }

    private static MockHttpServletRequestBuilder loginWithGoogle(String provider, String idToken) {
        return post("/api/auth/login/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content(googleLoginJson(provider, idToken));
    }

    private static String extractJwt(String body) {
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("phai co accessToken").isTrue();
        return m.group(1);
    }

    /** Đọc thẳng claim scope ra chuỗi, không gọi endpoint nào — scope sai chứ không phải 403 sai. */
    private static String extractScope(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"scope\":\"([^\"]*)\"").matcher(payload);
            assertThat(m.find()).as("token phai co claim scope: " + payload).isTrue();
            return m.group(1);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("token khong phai JWT base64url hop le", e);
        }
    }

    /** Bắt mã OTP GỐC trong lá mail mới nhất — nơi duy nhất nó còn tồn tại sau khi băm BCrypt. */
    private String tokenFromMail() {
        assertThat(mailCatcher.sent).isNotEmpty();
        Matcher m = OTP_IN_MAIL.matcher(lastMail().body());
        assertThat(m.find()).as("mail phai co ma OTP 6 so").isTrue();
        return m.group(1);
    }

    private OutgoingMail lastMail() {
        return mailCatcher.sent.get(mailCatcher.sent.size() - 1);
    }

    /**
     * Bắt mọi lá OutgoingMail mà service phát ra. Dùng listener thật, không mock MailService:
     * thay bean bằng mock sẽ làm mất việc đăng ký @TransactionalEventListener, không bắt được
     * sự kiện nào.
     */
    static class MailCatcher {
        final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void receive(OutgoingMail m) {
            sent.add(m);
        }
    }

    /**
     * Thay bean kiểm ID token Firebase bằng stub: test đăng nhập Google không được gọi Google
     * thật (chữ ký thật do FirebaseIdTokenDecoderTest kiểm riêng, cũng không gọi mạng).
     */
    static class FakeGoogleDecoder implements FirebaseIdTokenDecoder {
        /** null = token không kiểm được. */
        FirebaseUser user;

        @Override
        public FirebaseUser decode(String idToken) {
            if (user == null) {
                throw new AppException(ErrorCode.BAD_CREDENTIALS, "ID token của Firebase không hợp lệ");
            }
            return user;
        }
    }

    @TestConfiguration
    static class TestConfig {

        /** Đồng hồ đóng băng để kiểm hết hạn và khoá 15 phút mà không phải chờ. */
        @Bean
        @Primary
        Clock testClock() {
            return FIXED_CLOCK;
        }

        @Bean
        MailCatcher thu() {
            return new MailCatcher();
        }

        /** @Primary vì ngoài đây còn bean thật; khai kiểu FakeGoogleDecoder để test ghi thẳng dữ liệu vào. */
        @Bean
        @Primary
        FakeGoogleDecoder fakeGoogle() {
            return new FakeGoogleDecoder();
        }
    }
}