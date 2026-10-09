package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nội dung game qua HTTP thật (MockMvc + H2): admin lưu nháp đúng hình dạng FE → phát hành SemVer → ai
 * cũng đọc được không cần token; bản đã phát hành ĐÓNG BĂNG (sửa nháp, phát hành bản mới, bản cũ vẫn
 * nguyên); {{ASSET_BASE}} trong JSON được thay bằng app.assets.base-url lúc trả; lùi bản chỉ đổi con trỏ.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "app.assets.base-url=https://cdn.test.local/"})
@AutoConfigureMockMvc
class ContentFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;

    /** Chương tối giản theo đúng khung FE (chapterNV2GameData): scenes[] với next trỏ nhau, ảnh dạng placeholder. */
    private static String chapter(String title, String nextOfFirst) {
        return """
                {"id":"chapter-1","title":"%s","version":"2.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chào","nextSceneId":"%s"},
                   {"id":"choice-1","type":"choice","options":[{"id":"a","text":"Tiết kiệm","nextSceneId":"end"}]},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """.formatted(title, nextOfFirst);
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("studio-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin");
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    @Test
    void draftPublishReadFreezeRollback() throws Exception {
        var asAdmin = admin();

        // Nháp sai cấu trúc: next trỏ cảnh không có → 400/1001, đúng ràng buộc "phong-ngủ không tồn tại".
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(chapter("Bad", "phong-ngu")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message").value(containsString("phong-ngu")));

        // Người lớn thường (không SCOPE_ADMIN) không vào được Studio.
        mvc.perform(put("/api/studio/chapters/CH01/draft")
                        .with(jwt().authorities(new SimpleGrantedAuthority("TYP_ACCOUNT")))
                        .contentType(MediaType.APPLICATION_JSON).content(chapter("Ok", "choice-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(3004));

        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(chapter("Lần 1", "choice-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.code").value("CH01"))
                .andExpect(jsonPath("$.result.chapterNumber").value(1))
                .andExpect(jsonPath("$.result.title").value("Lần 1"));

        // Chưa phát hành: FE hỏi bản hiện tại thì 404/1003, không phải lỗi máy chủ.
        mvc.perform(get("/api/content/current"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));

        // Phiên bản phải là SemVer.
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"v1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));

        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"1.0.0\",\"note\":\"bản đầu\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.current").value(true))
                .andExpect(jsonPath("$.result.chapters[0].code").value("CH01"));

        // Đọc công khai, không token; JSON trả về đúng khung FE, placeholder đã thành URL CDN.
        mvc.perform(get("/api/content/current"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-cache")))
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.chapters.length()").value(1));
        mvc.perform(get("/api/content/releases/1.0.0/chapters/ch01"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(jsonPath("$.result.title").value("Lần 1"))
                .andExpect(jsonPath("$.result.releaseVersion").value("1.0.0"))
                .andExpect(jsonPath("$.result.initialStats.SAVING").value(50))
                .andExpect(jsonPath("$.result.scenes[0].background").value("https://cdn.test.local/a/bg1.webp"))
                .andExpect(jsonPath("$.result.sprites.MOM.neutral").value(startsWith("https://cdn.test.local/")))
                .andExpect(jsonPath("$.result.scenes[1].options[0].nextSceneId").value("end"));
        mvc.perform(get("/api/content/releases/1.0.0/chapters/CH02"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(1003));

        // Sửa nháp rồi phát hành 1.0.1: bản 1.0.0 vẫn nguyên (đóng băng), bản hiện tại là 1.0.1.
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(chapter("Lần 2", "choice-1")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/content/chapters/CH01"))
                .andExpect(jsonPath("$.result.title").value("Lần 1"));   // nháp chưa phát hành
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.0\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6001));
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.current").value(true));
        mvc.perform(get("/api/content/current"))
                .andExpect(jsonPath("$.result.version").value("1.0.1"));
        mvc.perform(get("/api/content/releases/1.0.1/chapters/CH01"))
                .andExpect(jsonPath("$.result.title").value("Lần 2"));
        mvc.perform(get("/api/content/releases/1.0.0/chapters/CH01"))
                .andExpect(jsonPath("$.result.title").value("Lần 1"));

        // Lùi bản: chỉ đổi con trỏ, đúng một bản current trong danh sách.
        mvc.perform(post("/api/studio/releases/1.0.0/current").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.current").value(true));
        mvc.perform(get("/api/content/current"))
                .andExpect(jsonPath("$.result.version").value("1.0.0"));
        mvc.perform(get("/api/studio/releases").with(asAdmin))
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[?(@.current == true)].version").value("1.0.0"));
    }
}
