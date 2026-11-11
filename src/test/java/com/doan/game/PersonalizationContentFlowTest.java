package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cá nhân hoá trong luồng nội dung thật (spec build/spec-ca-nhan-hoa-2026-11-11.md): lưu nháp có biến thể hỏng → chỉ cảnh báo;
 * gửi duyệt → chặn 1001; chương hợp lệ phát hành xong thì đường công khai trả BẢN GỐC đã ghép — không lộ {{token}} / variants.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true",
        "app.assets.base-url=https://cdn.test.local/"})
@AutoConfigureMockMvc
class PersonalizationContentFlowTest {

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;

    @BeforeEach
    void wipeContent() {
        repos().wipe();
    }

    private static String chapter(String variantFields) {
        return """
                {"id":"chapter-1","title":"Tiền đầu tiên","version":"2.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50},
                 "personalization":{"tokens":{"item":{"default":"chiếc tai nghe","interest:SPORT":"đôi giày"}}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"{{item}} giảm còn 250.000đ",
                    "variants":{"gender:FEMALE":{%s}},"nextSceneId":"choice-1"},
                   {"id":"choice-1","type":"choice","options":[{"id":"a","text":"Mua {{item}}","nextSceneId":"end"},
                                                              {"id":"b","text":"Giữ tiền","nextSceneId":"end"}]},
                   {"id":"end","type":"ending"}
                 ],
                 "endingRules":{"priorityOrder":["x"],"endings":[]}}
                """.formatted(variantFields);
    }

    @Test
    void brokenVariantWarnsOnSaveAndBlocksSubmit() throws Exception {
        var admin = admin();
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(chapter("\"nextSceneId\":\"end\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.warnings[?(@ =~ /cá nhân hoá.*nextSceneId.*/)]").isNotEmpty());
        mvc.perform(post("/api/studio/chapters/CH01/submit").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeClass\":\"MINOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.message", containsString("cá nhân hoá")));
    }

    @Test
    void publicChapterIsTheComposedBaseVersion() throws Exception {
        String v = ContentTestSupport.publishFresh(mvc, admin(), repos(),
                Map.of("CH01", chapter("\"text\":\"Cậu ấy muốn {{item}} giá 250.000đ\"")), "ca nhan hoa");
        var res = mvc.perform(get("/api/content/releases/" + v + "/chapters/CH01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.scenes[0].text").value("chiếc tai nghe giảm còn 250.000đ"))
                .andExpect(jsonPath("$.result.scenes[0].background").value("https://cdn.test.local/a/bg1.webp"))
                .andExpect(jsonPath("$.result.scenes[1].options[0].text").value("Mua chiếc tai nghe"))
                .andExpect(jsonPath("$.result.releaseVersion").value(v))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(res).doesNotContain("{{").doesNotContain("\"personalization\"").doesNotContain("\"variants\"").doesNotContain("voiceReady");
        // Đóng băng: bản phát hành lưu sẵn danh sách khoá giọng (rỗng ở test) → phục vụ không cần hỏi DB.
        assertThat(releaseChapterRepo.findAll().get(0).getContent()).contains("\"voiceReady\"");
    }

    private RequestPostProcessor admin() {
        Account a = new Account();
        a.setEmail("perso-" + System.nanoTime() + "@test.local");
        a.setDisplayName("Admin");
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole role = new AccountRole();
        role.setAccount(a);
        role.setRole(Role.ADMIN);
        role.setGrantedAt(Instant.now());
        accountRoleRepo.save(role);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_ADMIN"));
    }

    private ContentTestSupport.Repos repos() {
        return new ContentTestSupport.Repos(releaseChapterRepo, releaseRepo, draftRepo, chapterRepo);
    }
}
