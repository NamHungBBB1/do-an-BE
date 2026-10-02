package com.doan.game;

import com.doan.game.configuration.ClockConfig;
import com.doan.game.entity.Account;
import com.doan.game.entity.Entitlement;
import com.doan.game.entity.LearnerGroup;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.enums.EntitlementSource;
import com.doan.game.enums.LearningContext;
import com.doan.game.enums.PlanKind;
import com.doan.game.enums.SlotStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.EntitlementRepository;
import com.doan.game.repository.LearnerGroupRepository;
import com.doan.game.repository.LearnerSlotRepository;
import com.doan.game.service.MailCanGui;
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
        "app.admin-emails=admin@fifteen.com"})
@AutoConfigureMockMvc
class AuthFlowTest {

    private static final Clock LUC = Clock.fixed(Instant.parse("2026-10-02T03:00:00Z"), ClockConfig.VN);
    private static final Pattern TRONG_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired EntitlementRepository entitlementRepo;
    @Autowired LearnerGroupRepository groupRepo;
    @Autowired LearnerSlotRepository slotRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired ThuThung thung;

    @BeforeEach
    void xoaThu() {
        thung.daGui.clear();
    }

    // ------------------------------------------------------------------ 1
    @Test
    void dangKyXongChuaXacMinhThiKhongDangNhapDuoc() throws Exception {
        String email = "khaxacminh-" + System.nanoTime() + "@test.local";
        dangKy(email);

        mvc.perform(dangNhap(email, "matkhau123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3011));
    }

    // ------------------------------------------------------------------ 2
    @Test
    void xacMinhRoiDangNhapDuocTokenThatDungDuocVoiEndpointKhac() throws Exception {
        String email = "hanhtinh-" + System.nanoTime() + "@test.local";
        dangKy(email);
        xacMinh(tokenTrongMail());

        var res = mvc.perform(dangNhap(email, "matkhau123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.result.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.result.expiresIn").value(168 * 3600))
                .andReturn();

        String jwt = jwtRa(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 4005 = giao dịch không có trong bảng. Quan trọng là KHÔNG phải 3003: token thật đã qua
        // được chặn cửa, và subject của nó đọc ra đúng id tài khoản.
        mvc.perform(get("/api/payments/123").header("Authorization", "Bearer " + jwt))
                .andExpect(jsonPath("$.code").value(4005));
    }

    // ------------------------------------------------------------------ 3
    @Test
    void dangKyTrungEmailKhacHoaThuongVanBiTuChoi() throws Exception {
        String email = "trung-" + System.nanoTime() + "@test.local";
        dangKy(email);

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email.toUpperCase(Locale.ROOT))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(3001));
    }

    // ------------------------------------------------------------------ 4
    @Test
    void saiMatKhauNamLanThiLanSauDungMatKhauDungVanBiKhoa() throws Exception {
        String email = "sailan-" + System.nanoTime() + "@test.local";
        dangKy(email);
        xacMinh(tokenTrongMail());
        thung.daGui.clear();

        for (int i = 1; i <= 5; i++) {
            mvc.perform(dangNhap(email, "saisaitinh-" + i))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(3002));
        }
        // Mật khẩu ĐÚNG ở lần 6 vẫn bị chặn. Đây là ca canh bẫy transaction: nếu dangNhap có
        // @Transactional thì 5 lần sai vừa rồi bị rollback, bộ đếm về 0 và lần này lọt.
        mvc.perform(dangNhap(email, "matkhau123"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value(3016));

        // Đăng nhập không bao giờ gửi mail — kể cả lần bị khoá.
        assertThat(thung.daGui).isEmpty();
    }

    // ------------------------------------------------------------------ 5
    @Test
    void tokenXacMinhDungHaiLanThiBiTuChoi() throws Exception {
        String email = "haiLan-" + System.nanoTime() + "@test.local";
        dangKy(email);
        String token = tokenTrongMail();

        xacMinh(token);
        mvc.perform(get("/api/auth/verify").param("token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(3012));
    }

    // ------------------------------------------------------------------ 6
    @Test
    void quenMatKhauEmailKhongTonTaiVanTra200VaKhongGuiMail() throws Exception {
        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"khongco-that-" + System.nanoTime() + "@test.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(thung.daGui).isEmpty();
    }

    // ------------------------------------------------------------------ 7
    @Test
    void datLaiMatKhauRoiMatKhauCuHong() throws Exception {
        String email = "datlai-" + System.nanoTime() + "@test.local";
        dangKy(email);
        xacMinh(tokenTrongMail());

        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + tokenTrongMail() + "\",\"newPassword\":\"matkhaumoi123\"}"))
                .andExpect(status().isOk());

        mvc.perform(dangNhap(email, "matkhau123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(3002));
        mvc.perform(dangNhap(email, "matkhaumoi123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty());
    }

    // ------------------------------------------------------------------ 8
    @Test
    void coGoiParenConHanThiTokenCoScopeParent() throws Exception {
        String email = "cogoi-" + System.nanoTime() + "@test.local";
        dangKy(email);
        xacMinh(tokenTrongMail());

        Account a = accountRepo.findByEmailIgnoreCase(email).orElseThrow();
        Entitlement e = new Entitlement();
        e.setAccount(a);
        e.setKind(PlanKind.PARENT);
        e.setStartsOn(LocalDate.now(LUC).minusDays(1));
        e.setExpiresOn(LocalDate.now(LUC).plusMonths(3));
        e.setSource(EntitlementSource.ADMIN);
        e.setGrantedBy(a);
        e.setCreatedAt(Instant.now(LUC));
        entitlementRepo.save(e);

        var res = mvc.perform(dangNhap(email, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn();
        String jwt = jwtRa(res.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // /api/slots/** chặn bằng SCOPE_PARENT | SCOPE_TEACHER. Không 403 tức là token đã mang scope.
        var tren = mvc.perform(post("/api/slots")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Be\",\"context\":\"FAMILY\"}"))
                .andReturn();
        assertThat(tren.getResponse().getStatus()).isNotEqualTo(403);
        assertThat(tren.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("\"code\":3004");
    }

    // ------------------------------------------------------------------ 9
    @Test
    void treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa() throws Exception {
        String code = "MA" + (System.nanoTime() % 100000);
        slot(code, "123456", SlotStatus.ACTIVE);

        mvc.perform(dangNhapTre(code, "123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.result.expiresIn").value(8 * 3600));

        for (int i = 1; i <= 5; i++) {
            mvc.perform(dangNhapTre(code, "99999" + i))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(3002));
        }
        // PIN đúng vẫn vào không được. Khoá 15 phút tính từ lockedUntil, không có job mở khoá.
        mvc.perform(dangNhapTre(code, "123456"))
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
    void emailChuoiConCuaEmailAdminKhongDuocLenQuyenAdmin() throws Exception {
        String chuoiCon = "min@fifteen.com";
        dangKy(chuoiCon);
        xacMinh(tokenTrongMail());

        var res = mvc.perform(dangNhap(chuoiCon, "matkhau123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(scopeRa(jwtRa(res.getResponse().getContentAsString(StandardCharsets.UTF_8))))
                .doesNotContain("ADMIN");

        // Đối chứng: chính email trong danh sách thì vẫn phải được cấp, không phải hỏng vì tôi sửa lỗi.
        dangKy("admin@fifteen.com");
        xacMinh(tokenTrongMail());

        var res2 = mvc.perform(dangNhap("admin@fifteen.com", "matkhau123"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(scopeRa(jwtRa(res2.getResponse().getContentAsString(StandardCharsets.UTF_8))))
                .contains("ADMIN");
    }

    // ------------------------------------------------------------------ 11
    /**
     * Link đặt lại mật khẩu trong mail là GET, mà đổi mật khẩu phải POST. Không có trang nhận liệu
     * thì bấm link là 405 và không ai đổi được mật khẩu.
     *
     * GET cố Ý KHÔNG tiêu token: trình đọc mail tự mở sẵn link để quét virus, tiêu token ở đó là
     * người dùng bấm link thật của mình thì gặp "link không đúng".
     */
    @Test
    void linkDatLaiMatKhauTrongMailMoRaDuocTrangVaKhongTieuToken() throws Exception {
        String email = "formreset-" + System.nanoTime() + "@test.local";
        dangKy(email);
        xacMinh(tokenTrongMail());

        mvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String token = tokenTrongMail();

        mvc.perform(get("/api/auth/password/reset").param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"newPassword\"")))
                .andExpect(content().string(containsString(token)));

        // Mở trang xong token còn sống: POST form mới thực sự tiêu nó.
        mvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", token)
                        .param("newPassword", "matkhaumoi123"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã đổi mật khẩu")));

        mvc.perform(dangNhap(email, "matkhau123"))
                .andExpect(status().isUnauthorized());
        mvc.perform(dangNhap(email, "matkhaumoi123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.accessToken").isNotEmpty());
    }

    // ------------------------------------------------------------------ dựng dữ liệu
    private void dangKy(String email) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private void xacMinh(String token) throws Exception {
        mvc.perform(get("/api/auth/verify").param("token", token)).andExpect(status().isOk());
    }

    private LearnerSlot slot(String code, String pin, SlotStatus status) {
        Account owner = new Account();
        owner.setEmail("slot-chu-" + System.nanoTime() + "@test.local");
        owner.setDisplayName("Chu slot");
        owner.setCreatedAt(Instant.now(LUC));
        owner = accountRepo.save(owner);

        LearnerGroup g = new LearnerGroup();
        g.setOwner(owner);
        g.setContext(LearningContext.FAMILY);
        g.setOpenContext(LearningContext.FAMILY);
        g.setName("Nha test");
        g.setSlotLimit(4);
        g.setOpenedAt(Instant.now(LUC));
        g = groupRepo.save(g);

        LearnerSlot s = new LearnerSlot();
        s.setGroup(g);
        s.setCode(code);
        s.setPinHash(encoder.encode(pin));
        s.setDisplayName("Be");
        s.setStatus(status);
        s.setFailedAttempts(0);
        s.setCreatedAt(Instant.now(LUC));
        return slotRepo.save(s);
    }

    private static String registerJson(String email) {
        return "{\"email\":\"" + email + "\",\"phone\":\"0900000000\","
                + "\"password\":\"matkhau123\",\"displayName\":\"Nguyen Test\"}";
    }

    private static MockHttpServletRequestBuilder dangNhap(String email, String matKhau) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + matKhau + "\"}");
    }

    private static MockHttpServletRequestBuilder dangNhapTre(String code, String pin) {
        return post("/api/slots/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"pin\":\"" + pin + "\"}");
    }

    private static String jwtRa(String body) {
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as("phai co accessToken").isTrue();
        return m.group(1);
    }

    /** Đọc thẳng claim scope ra chuỗi, không gọi endpoint nào — scope sai chứ không phải 403 sai. */
    private static String scopeRa(String jwt) {
        try {
            String[] phan = jwt.split("\\.");
            String payload = new String(Base64.getUrlDecoder().decode(phan[1]), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"scope\":\"([^\"]*)\"").matcher(payload);
            assertThat(m.find()).as("token phai co claim scope: " + payload).isTrue();
            return m.group(1);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("token khong phai JWT base64url hop le", e);
        }
    }

    /** Bắt token GỐC trong nội dung mail — nơi duy nhất nó còn tồn tại sau khi băm SHA-256. */
    private String tokenTrongMail() {
        assertThat(thung.daGui).isNotEmpty();
        Matcher m = TRONG_LINK.matcher(thung.daGui.get(thung.daGui.size() - 1).body());
        assertThat(m.find()).as("mail phai co link chua token").isTrue();
        return m.group(1);
    }

    /**
     * Bắt mọi lá MailCanGui mà service phát ra. Dùng listener thật, không mock MailService:
     * thay bean bằng mock sẽ làm mất việc đăng ký @TransactionalEventListener, không bắt được
     * sự kiện nào.
     */
    static class ThuThung {
        final List<MailCanGui> daGui = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void nhan(MailCanGui m) {
            daGui.add(m);
        }
    }

    @TestConfiguration
    static class CauHinhTest {

        /** Đồng hồ đóng băng để kiểm hết hạn và khoá 15 phút mà không phải chờ. */
        @Bean
        @Primary
        Clock dongHo() {
            return LUC;
        }

        @Bean
        ThuThung thu() {
            return new ThuThung();
        }
    }
}