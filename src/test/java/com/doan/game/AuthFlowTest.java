package com.doan.game;

import com.doan.game.auth.AuthService;
import com.doan.game.auth.domain.Account;
import com.doan.game.auth.repository.AccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giữ đúng những chốt mà hỏng thì không ai phát hiện bằng mắt:
 * hạn mức slot, khoá PIN khi dò, và ranh giới người lớn / trẻ.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired AuthService authService;

    /** Mỗi test một email khác nhau — H2 dùng chung cho cả lớp test. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Test
    void phu_huynh_tao_slot_va_tre_dang_nhap_bang_qr_pin() throws Exception {
        String token = adultWithPlans(true, false);

        JsonNode slot = call(post("/api/slots").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"Bé An","context":"FAMILY"}"""), 200);

        String code = slot.at("/result/code").asText();
        String pin = slot.at("/result/pin").asText();
        assertThat(pin).matches("[0-9]{6}");
        assertThat(code).matches("[2-9A-HJ-NP-Z]{8}");

        JsonNode login = call(post("/api/auth/child/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Login(code, pin))), 200);
        assertThat(login.at("/result/typ").asText()).isEqualTo("child");

        String childToken = login.at("/result/token").asText();
        JsonNode me = call(get("/api/auth/me").header("Authorization", "Bearer " + childToken), 200);
        assertThat(me.at("/result/context").asText()).isEqualTo("FAMILY");
        assertThat(me.at("/result/displayName").asText()).isEqualTo("Bé An");

        // Token của trẻ KHÔNG mở được cửa của người lớn — ranh giới phải chặn ở cổng.
        call(post("/api/slots").header("Authorization", "Bearer " + childToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"Lén tạo","context":"FAMILY"}"""), 403);
    }

    @Test
    void phu_huynh_chi_duoc_4_slot() throws Exception {
        String token = adultWithPlans(true, false);
        for (int i = 1; i <= 4; i++) newSlot(token, "Con " + i, "FAMILY", 200);
        JsonNode over = newSlot(token, "Con 5", "FAMILY", 409);
        assertThat(over.at("/code").asInt()).isEqualTo(3006);
    }

    @Test
    void chua_mua_goi_thi_khong_tao_duoc_slot() throws Exception {
        JsonNode denied = newSlot(adultWithPlans(false, false), "Con", "FAMILY", 403);
        // Chưa có gói thì scope là GUEST — Security chặn ngay ở filter, chưa vào controller.
        assertThat(denied.at("/code").asInt()).isEqualTo(3004);
    }

    @Test
    void do_pin_5_lan_thi_khoa_slot() throws Exception {
        String token = adultWithPlans(true, false);
        String code = newSlot(token, "Bé Bo", "FAMILY", 200).at("/result/code").asText();

        for (int i = 0; i < 5; i++) childLogin(code, "000000", 401);
        // Lần thứ 6 khoá hẳn, kể cả PIN đúng cũng không vào được.
        assertThat(childLogin(code, "000000", 423).at("/code").asInt()).isEqualTo(3008);
    }

    @Test
    void ket_thuc_lop_tra_lai_slot_va_chan_ma_cu() throws Exception {
        String token = adultWithPlans(false, true);
        JsonNode s = newSlot(token, "Học sinh 1", "CLASS", 200);
        String code = s.at("/result/code").asText();
        String pin = s.at("/result/pin").asText();

        childLogin(code, pin, 200);

        JsonNode ended = call(post("/api/slots/end-class").header("Authorization", "Bearer " + token), 200);
        assertThat(ended.at("/result/archived").asInt()).isEqualTo(1);

        // Slot đã trả lại: mã cũ hết vào được, nhưng data không bị xoá.
        assertThat(childLogin(code, pin, 410).at("/code").asInt()).isEqualTo(3009);
        assertThat(newSlot(token, "Học sinh mới", "CLASS", 200).at("/result/code").asText())
                .isNotEqualTo(code);
    }

    @Test
    void khong_co_token_thi_khong_vao_duoc() throws Exception {
        assertThat(call(get("/api/auth/me"), 401).at("/code").asInt()).isEqualTo(3003);
    }

    // ---- cửa chặn spam: xác thực email ----

    @Test
    void chua_xac_thuc_email_thi_khong_mua_duoc_goi() throws Exception {
        String token = register("chuaxacthuc" + SEQ.incrementAndGet() + "@gmail.com");

        JsonNode me = call(get("/api/auth/me").header("Authorization", "Bearer " + token), 200);
        assertThat(me.at("/result/emailVerified").asBoolean()).isFalse();

        JsonNode denied = call(post("/api/auth/plan").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Plan(true, false))), 403);
        assertThat(denied.at("/code").asInt()).isEqualTo(3011);
    }

    @Test
    void bam_link_xong_thi_mua_duoc_goi_va_me_bao_da_xac_thuc() throws Exception {
        String email = "xacthuc" + SEQ.incrementAndGet() + "@gmail.com";
        String token = register(email);
        clickVerifyLink(email);

        String after = call(post("/api/auth/plan").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Plan(true, false))), 200)
                .at("/result/token").asText();
        JsonNode me = call(get("/api/auth/me").header("Authorization", "Bearer " + after), 200);
        assertThat(me.at("/result/emailVerified").asBoolean()).isTrue();
    }

    @Test
    void link_dung_hai_lan_thi_lan_hai_hong() throws Exception {
        String email = "motlan" + SEQ.incrementAndGet() + "@gmail.com";
        register(email);
        Account a = accounts.findByEmailIgnoreCase(email).orElseThrow();
        a.setVerifyTokenSentAt(null);
        accounts.save(a);
        String raw = authService.issueVerification(a.getId());

        mvc.perform(get("/api/auth/verify").param("token", raw)).andExpect(status().isOk());
        // Lần hai: token đã bị xoá khỏi DB nên không tra ra ai -> trang báo lỗi, không phải 500.
        String html = mvc.perform(get("/api/auth/verify").param("token", raw))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Không xác thực được");
    }

    @Test
    void gui_lai_ngay_sau_khi_dang_ky_thi_bi_chan() throws Exception {
        String token = register("guilai" + SEQ.incrementAndGet() + "@gmail.com");
        JsonNode r = call(post("/api/auth/verify/resend").header("Authorization", "Bearer " + token), 429);
        assertThat(r.at("/code").asInt()).isEqualTo(3015);
    }

    // ---- tiện ích ----

    private String register(String email) throws Exception {
        return call(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                        new Register(email, "0912345678", "matkhau123", "Phụ huynh"))), 200)
                .at("/result/token").asText();
    }

    /**
     * Bấm link xác thực y như người dùng thật: xin token gốc rồi GET /api/auth/verify.
     * Phải xoá verifyTokenSentAt trước vì register vừa gửi xong — đó là chống bấm
     * "gửi lại" liên tục, ở đây giả lập một phút đã trôi qua.
     */
    private void clickVerifyLink(String email) throws Exception {
        Account a = accounts.findByEmailIgnoreCase(email).orElseThrow();
        a.setVerifyTokenSentAt(null);
        accounts.save(a);
        String raw = authService.issueVerification(a.getId());
        mvc.perform(get("/api/auth/verify").param("token", raw))
                .andExpect(status().isOk());
    }

    private String adultWithPlans(boolean parent, boolean teacher) throws Exception {
        String email = "ph" + SEQ.incrementAndGet() + "@gmail.com";
        String token = register(email);
        if (!parent && !teacher) return token;
        clickVerifyLink(email);
        // Mua gói xong phải lấy token MỚI: vai nằm trong token.
        return call(post("/api/auth/plan").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Plan(parent, teacher))), 200)
                .at("/result/token").asText();
    }

    private JsonNode newSlot(String token, String name, String ctx, int status) throws Exception {
        return call(post("/api/slots").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Slot(name, ctx))), status);
    }

    private JsonNode childLogin(String code, String pin, int status) throws Exception {
        return call(post("/api/auth/child/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Login(code, pin))), status);
    }

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder rb, int status) throws Exception {
        MvcResult r = mvc.perform(rb).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private record Register(String email, String phone, String password, String displayName) {}
    private record Login(String code, String pin) {}
    private record Slot(String displayName, String context) {}
    private record Plan(boolean parentPlan, boolean teacherPlan) {}
}
