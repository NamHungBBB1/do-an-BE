package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.AiProviderKind;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.AiKeyRepository;
import com.doan.game.service.AiKeyProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Khoá AI trên trang admin (24/10): nhập rồi thử luôn, không bao giờ trả khoá thật (chỉ 4 ký tự cuối), DB chỉ giữ bản mã hoá,
 * thử lại khi hết lượt thì nghỉ, dừng / bật, xoá; người không phải ADMIN bị chặn. Nhà cung cấp giả — không gọi mạng.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiKeyFlowTest {

    private static final String KEY = "AIzaSy-test-key-0000-abcd";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired AiKeyRepository keyRepo;
    @MockitoBean AiKeyProbe probe;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        keyRepo.deleteAll();
    }

    @Test
    void addTestDisableDelete() throws Exception {
        RequestPostProcessor admin = as(Role.ADMIN, "SCOPE_ADMIN");
        when(probe.probe(eq(AiProviderKind.GEMINI), eq(KEY))).thenReturn(new AiKeyProbe.Result(AiKeyProbe.Outcome.OK, "200"));

        String body = mvc.perform(post("/api/admin/ai-keys").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"gemini\",\"alias\":\"gemini-1\",\"key\":\"" + KEY + "\",\"priority\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.masked").value("••••abcd"))
                .andExpect(jsonPath("$.result.enabled").value(true))
                .andExpect(jsonPath("$.result.lastTestOk").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(KEY);
        String id = json.readTree(body).path("result").path("id").asText();
        // DB chỉ giữ bản mã hoá.
        assertThat(keyRepo.findAll()).singleElement().satisfies(k -> {
            assertThat(k.getSecretEnc()).doesNotContain(KEY);
            assertThat(k.getLast4()).isEqualTo("abcd");
        });

        // Trùng tên gợi nhớ → 9002; khoá có khoảng trắng → 9001.
        mvc.perform(post("/api/admin/ai-keys").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GEMINI\",\"alias\":\"GEMINI-1\",\"key\":\"" + KEY + "x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9002));
        mvc.perform(post("/api/admin/ai-keys").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"GEMINI\",\"alias\":\"g2\",\"key\":\"co khoang trang 123\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(9001));

        // Thử lại khi hết lượt: ghi lỗi + nghỉ; khoá thật được giải mã đúng để gửi đi.
        when(probe.probe(any(), eq(KEY))).thenReturn(new AiKeyProbe.Result(AiKeyProbe.Outcome.QUOTA, "HTTP 429"));
        mvc.perform(post("/api/admin/ai-keys/" + id + "/test").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.lastTestOk").value(false))
                .andExpect(jsonPath("$.result.lastError").value("QUOTA HTTP 429"))
                .andExpect(jsonPath("$.result.coolDownUntil").isNotEmpty());

        // Dừng / bật lại, rồi xoá.
        mvc.perform(post("/api/admin/ai-keys/" + id + "/disable").with(admin))
                .andExpect(jsonPath("$.result.enabled").value(false));
        mvc.perform(post("/api/admin/ai-keys/" + id + "/enable").with(admin))
                .andExpect(jsonPath("$.result.enabled").value(true));
        mvc.perform(delete("/api/admin/ai-keys/" + id).with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/ai-keys").with(admin)).andExpect(jsonPath("$.result.length()").value(0));
    }

    @Test
    void onlyAdmin() throws Exception {
        mvc.perform(get("/api/admin/ai-keys").with(as(Role.GAME_MANAGER, "SCOPE_GAME_MANAGER")))
                .andExpect(status().isForbidden());
    }

    private RequestPostProcessor as(Role role, String scope) {
        Account a = new Account();
        a.setEmail("ai-" + role.name().toLowerCase() + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(role.name());
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole r = new AccountRole();
        r.setAccount(a);
        r.setRole(role);
        r.setGrantedAt(Instant.now());
        accountRoleRepo.save(r);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority(scope));
    }
}
