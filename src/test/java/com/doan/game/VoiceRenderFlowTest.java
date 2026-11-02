package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.Role;
import com.doan.game.enums.VoiceClipStatus;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.VoiceClipRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Giọng đọc 03/11 (spec kho ảnh + giọng mục 4.2–4.6): lưu nháp → câu chưa có tệp = MISSING → Editor bấm Sinh giọng → PENDING →
 * máy sinh (khoá header) lấy việc, nộp mp3 → tệp v/&lt;key&gt;.mp3, READY, nháp tự có voiceUrl. Sửa câu → gỡ giọng; sửa về câu cũ →
 * tự gắn lại giọng cũ, KHÔNG xếp sinh lại. Không gọi GitHub (test không có token).
 */
@SpringBootTest(properties = {"app.voice.worker-key=wk-test-123", "app.assets.dir=${java.io.tmpdir}/finteen-voice-test"})
@AutoConfigureMockMvc
class VoiceRenderFlowTest {

    private static final String WK = "X-Voice-Worker-Key";

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired VoiceClipRepository clipRepo;
    @Autowired ObjectMapper om;

    private static String chapter(String meLine) {
        return """
                {"id":"chapter-9","title":"Chuong giong","version":"1.0.0","initialStats":{"SAVING":50},
                 "scenes":[
                   {"id":"s1","type":"dialogue","speakerId":"ME","text":"%s","nextSceneId":"s2"},
                   {"id":"s2","type":"dialogue","speakerId":"AN","text":"Con sẽ để dành một nửa.","nextSceneId":"s3"},
                   {"id":"s3","type":"dialogue","speakerId":"AN","text":"Tắt giọng câu này.","voice":"off","nextSceneId":"s4"},
                   {"id":"s4","type":"dialogue","speakerId":"AN","text":"Con sẽ để dành một nửa.","nextSceneId":"end"},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """.formatted(meLine);
    }

    @Test
    void renderOnlyNewLinesThenAttach() throws Exception {
        RequestPostProcessor editor = as(Role.GAME_EDITOR, "SCOPE_GAME_EDITOR");
        String v1 = chapter("Con được 500.000đ tiền mừng tuổi.");
        mvc.perform(put("/api/studio/chapters/CH09/draft").with(editor).contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk());

        // 3 câu đọc (s3 tắt), s2 và s4 trùng chữ + giọng → cùng khoá
        mvc.perform(get("/api/studio/chapters/CH09/voice").with(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.lines").value(3))
                .andExpect(jsonPath("$.result.missing").value(3))
                .andExpect(jsonPath("$.result.items[0].readText").value("Con được 500.000 đồng tiền mừng tuổi."));

        // Reviewer không được bấm sinh
        mvc.perform(post("/api/studio/chapters/CH09/voice").with(as(Role.GAME_REVIEWER, "SCOPE_GAME_REVIEWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/studio/chapters/CH09/voice").with(editor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.queued").value(2))          // 2 khoá: s1, s2 (= s4)
                .andExpect(jsonPath("$.result.dispatched").value(false))
                .andExpect(jsonPath("$.result.dispatchNote").value("chưa cấu hình GITHUB_VOICE_TOKEN"));
        mvc.perform(post("/api/studio/chapters/CH09/voice").with(editor))   // bấm lại: không xếp trùng
                .andExpect(jsonPath("$.result.queued").value(0))
                .andExpect(jsonPath("$.result.alreadyPending").value(2));

        // máy sinh: sai / thiếu khoá → 401
        mvc.perform(get("/api/voice-worker/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/voice-worker/jobs").header(WK, "sai")).andExpect(status().isUnauthorized());
        String body = mvc.perform(get("/api/voice-worker/jobs").header(WK, "wk-test-123"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode jobs = om.readTree(body).path("result");
        String meKey = null;
        String anKey = null;
        for (JsonNode j : jobs) {
            if (j.path("profile").asText().equals("nu-luu-loat")) {
                meKey = j.path("key").asText();
            } else if (j.path("readText").asText().equals("Con sẽ để dành một nửa.")) {
                anKey = j.path("key").asText();
            }
        }
        assertThat(meKey).isNotNull();
        assertThat(anKey).isNotNull();

        // nộp tệp không phải mp3 → 6008; nộp mp3 → DONE, tệp nằm ở kho
        mvc.perform(multipart("/api/voice-worker/clips/" + meKey).file(new MockMultipartFile("file", "x.mp3", "audio/mpeg",
                        "<html>".getBytes())).header(WK, "wk-test-123"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(6008));
        byte[] mp3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0};
        mvc.perform(multipart("/api/voice-worker/clips/" + meKey).file(new MockMultipartFile("file", "x.mp3", "audio/mpeg", mp3))
                        .param("seconds", "3.4").header(WK, "wk-test-123"))
                .andExpect(status().isOk());
        Path saved = Path.of(System.getProperty("java.io.tmpdir"), "finteen-voice-test", "v", meKey + ".mp3");
        assertThat(Files.readAllBytes(saved)).isEqualTo(mp3);
        mvc.perform(post("/api/voice-worker/clips/" + anKey + "/failed").header(WK, "wk-test-123")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"error\":\"onnx hỏng\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/studio/chapters/CH09/voice").with(editor))
                .andExpect(jsonPath("$.result.ready").value(1))
                .andExpect(jsonPath("$.result.failed").value(2))
                .andExpect(jsonPath("$.result.items[1].error").value("onnx hỏng"));
        // đọc nháp: câu vừa sinh có voiceUrl ngay, câu lỗi / tắt thì không
        mvc.perform(get("/api/studio/chapters/CH09/draft").with(editor))
                .andExpect(jsonPath("$.result.content.scenes[0].voiceUrl").value(org.hamcrest.Matchers.endsWith("/v/" + meKey + ".mp3")))
                .andExpect(jsonPath("$.result.content.scenes[1].voiceUrl").doesNotExist())
                .andExpect(jsonPath("$.result.content.scenes[2].voiceUrl").doesNotExist());

        // sửa câu của Mẹ → khoá mới → gỡ giọng; sửa về câu cũ → tự gắn lại giọng cũ, không xếp sinh lại
        mvc.perform(put("/api/studio/chapters/CH09/draft").with(editor).contentType(MediaType.APPLICATION_JSON)
                .content(chapter("Con được 600.000đ tiền mừng tuổi."))).andExpect(status().isOk());
        mvc.perform(get("/api/studio/chapters/CH09/draft").with(editor))
                .andExpect(jsonPath("$.result.content.scenes[0].voiceUrl").doesNotExist());
        mvc.perform(put("/api/studio/chapters/CH09/draft").with(editor).contentType(MediaType.APPLICATION_JSON).content(v1))
                .andExpect(status().isOk());
        mvc.perform(get("/api/studio/chapters/CH09/voice").with(editor))
                .andExpect(jsonPath("$.result.items[0].status").value("READY"));
        // editor tự ghi voiceUrl bậy → BE gỡ (chỉ BE ghi voiceUrl)
        String forged = v1.replace("\"Con sẽ để dành một nửa.\",\"nextSceneId\":\"s3\"",
                "\"Con sẽ để dành một nửa.\",\"voiceUrl\":\"https://x/v/bia.mp3\",\"nextSceneId\":\"s3\"");
        mvc.perform(put("/api/studio/chapters/CH09/draft").with(editor).contentType(MediaType.APPLICATION_JSON).content(forged))
                .andExpect(status().isOk());
        mvc.perform(get("/api/studio/chapters/CH09/draft").with(editor))
                .andExpect(jsonPath("$.result.content.scenes[1].voiceUrl").doesNotExist());

        // FAILED → bấm sinh lại thì xếp lại PENDING
        mvc.perform(post("/api/studio/chapters/CH09/voice").with(editor)).andExpect(jsonPath("$.result.queued").value(1));
        assertThat(clipRepo.findByClipKey(anKey).orElseThrow().getStatus()).isEqualTo(VoiceClipStatus.PENDING);
    }

    @Test
    void renderedClipsSeededFromClipsJson() {
        // 52 giọng sinh tay trước đây (ops/voice/clips.json) có mặt DONE → câu giống hệt dùng lại, không sinh lại
        assertThat(clipRepo.findByClipKey("364b59d6f1d5c5faccc5").orElseThrow().getStatus()).isEqualTo(VoiceClipStatus.DONE);
        assertThat(clipRepo.count()).isGreaterThanOrEqualTo(52);
    }

    private RequestPostProcessor as(Role role, String scope) {
        Account a = new Account();
        a.setEmail("voice-render-" + role.name().toLowerCase() + "-" + System.nanoTime() + "@test.local");
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
