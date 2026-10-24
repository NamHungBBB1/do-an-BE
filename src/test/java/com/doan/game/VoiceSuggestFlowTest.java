package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.AiProviderKind;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.AiKeyRepository;
import com.doan.game.service.AiKeyProbe;
import com.doan.game.service.AiTextClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Gợi ý cách đọc (24/10, Hưng: gom lại gọi 1 lần): chỉ câu thoại có người nói VÀ dễ đọc sai (số, ký hiệu, viết tắt) được gửi AI,
 * MỘT lần gọi cho cả chương; chương không có câu nào như vậy → 0 lần gọi; AI bịa mã cảnh / không đổi gì → bỏ; không có khoá → 9003.
 * AI và thử khoá đều giả — không gọi mạng.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VoiceSuggestFlowTest {

    private static final String KEY = "AIzaSy-voice-test-0000-wxyz";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired AiKeyRepository keyRepo;
    @MockitoBean AiKeyProbe probe;
    @MockitoBean AiTextClient ai;

    private static final String CHAPTER = """
            {"id":"chapter-9","title":"Chuong thu giong","version":"1.0.0","initialStats":{"SAVING":50},
             "scenes":[
               {"id":"s1","type":"dialogue","speaker":"Me","text":"Con được 500.000đ tiền mừng tuổi.","nextSceneId":"s2"},
               {"id":"s2","type":"dialogue","speaker":"An","text":"Con sẽ để dành một nửa.","nextSceneId":"s3"},
               {"id":"s3","type":"dialogue","speaker":"An","text":"Mã OTP là gì hả mẹ?","nextSceneId":"s4"},
               {"id":"s4","type":"dialogue","text":"Lãi suất 5% một năm.","nextSceneId":"s5"},
               {"id":"s5","type":"dialogue","speaker":"Me","text":"Lãi 5%/năm.","spokenText":"lãi năm phần trăm một năm","nextSceneId":"end"},
               {"id":"end","type":"ending"}
             ],
             "endingRules":{"priorityOrder":["x"],"endings":[]}}
            """;

    @BeforeEach
    void clean() {
        keyRepo.deleteAll();
    }

    @Test
    void oneCallPerChapterOnlyRiskyLines() throws Exception {
        RequestPostProcessor admin = as(Role.ADMIN, "SCOPE_ADMIN");
        mvc.perform(put("/api/studio/chapters/CH09/draft").with(admin).contentType(MediaType.APPLICATION_JSON).content(CHAPTER))
                .andExpect(status().isOk());
        // chưa có khoá → 409 / 9003, không gọi AI
        mvc.perform(post("/api/studio/chapters/CH09/voice/suggest").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(9003));
        verify(ai, never()).generateJson(any(), anyString(), anyString());

        when(probe.probe(eq(AiProviderKind.GEMINI), eq(KEY))).thenReturn(new AiKeyProbe.Result(AiKeyProbe.Outcome.OK, "200"));
        mvc.perform(post("/api/admin/ai-keys").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"gemini\",\"alias\":\"voice-1\",\"key\":\"" + KEY + "\",\"priority\":1}"))
                .andExpect(status().isOk());
        when(ai.model(AiProviderKind.GEMINI)).thenReturn("gemini-test");
        when(ai.generateJson(eq(AiProviderKind.GEMINI), eq(KEY), anyString())).thenReturn("""
                {"items":[
                  {"sceneId":"s1","spokenText":"Con được năm trăm nghìn đồng tiền mừng tuổi.","reasons":["số tiền"]},
                  {"sceneId":"s3","spokenText":"Mã Ô Tê Pê là gì hả mẹ?","reasons":["viết tắt"]},
                  {"sceneId":"bia","spokenText":"x"},
                  {"sceneId":"s2","spokenText":"Con sẽ để dành một nửa."}
                ]}""");

        mvc.perform(post("/api/studio/chapters/CH09/voice/suggest").with(as(Role.GAME_EDITOR, "SCOPE_GAME_EDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.source").value("GEMINI"))
                .andExpect(jsonPath("$.result.model").value("gemini-test"))
                .andExpect(jsonPath("$.result.lines").value(3))       // s1, s2, s3 (s4 không người nói, s5 đã chốt spokenText)
                .andExpect(jsonPath("$.result.sentToAi").value(2))    // s2 thuần chữ → không gửi
                .andExpect(jsonPath("$.result.aiCalls").value(1))
                .andExpect(jsonPath("$.result.suggestions.length()").value(2))
                .andExpect(jsonPath("$.result.suggestions[0].sceneId").value("s1"))
                .andExpect(jsonPath("$.result.suggestions[0].text").value("Con được 500.000đ tiền mừng tuổi."))
                .andExpect(jsonPath("$.result.suggestions[1].reasons[0]").value("viết tắt"));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(ai, times(1)).generateJson(eq(AiProviderKind.GEMINI), eq(KEY), prompt.capture());
        assertThat(prompt.getValue()).contains("s1 | Con được 500.000đ").contains("s3 | Mã OTP")
                .doesNotContain("để dành một nửa").doesNotContain("Lãi suất").doesNotContain("5%/năm");
    }

    @Test
    void chapterWithoutRiskyLinesCostsNoCall() throws Exception {
        RequestPostProcessor admin = as(Role.ADMIN, "SCOPE_ADMIN");
        String plain = CHAPTER.replace("500.000đ", "nhiều tiền").replace("Mã OTP", "Mã xác thực");
        mvc.perform(put("/api/studio/chapters/CH09/draft").with(admin).contentType(MediaType.APPLICATION_JSON).content(plain))
                .andExpect(status().isOk());
        mvc.perform(post("/api/studio/chapters/CH09/voice/suggest").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.aiCalls").value(0))
                .andExpect(jsonPath("$.result.suggestions.length()").value(0));
        verify(ai, never()).generateJson(any(), anyString(), anyString());
    }

    private RequestPostProcessor as(Role role, String scope) {
        Account a = new Account();
        a.setEmail("voice-" + role.name().toLowerCase() + "-" + System.nanoTime() + "@test.local");
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
