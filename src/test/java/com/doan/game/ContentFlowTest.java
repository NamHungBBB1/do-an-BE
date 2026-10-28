package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.ChangeClass;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired ReleaseChapterRepository releaseChapterRepo;
    @Autowired GameReleaseRepository releaseRepo;
    @Autowired ChapterDraftRepository draftRepo;
    @Autowired ChapterRepository chapterRepo;

    /**
     * Mọi class test dùng chung một H2 (jdbc:h2:mem:doan, DB_CLOSE_DELAY=-1) và surefire chạy class theo
     * thứ tự filesystem — Linux khác Windows. Trên CI (PR #26, 12/10) PlayFlowTest chạy trước và đã phát
     * hành 1.0.2, làm luồng này (mong "chưa phát hành" → 404, danh sách 2 bản) hỏng. Xoá sạch nội dung
     * trước khi chạy để luồng luôn bắt đầu từ trống, không phụ thuộc thứ tự. Thứ tự xoá theo khoá ngoại.
     */
    @BeforeEach
    void wipeContent() {
        releaseChapterRepo.deleteAll();
        releaseRepo.deleteAll();
        draftRepo.deleteAll();
        chapterRepo.deleteAll();
    }

    /** Chương tối giản theo đúng khung FE (chapterNV2GameData): scenes[] với next trỏ nhau, ảnh dạng placeholder. */
    private static String chapter(String title, String nextOfFirst) {
        return """
                {"id":"chapter-1","title":"%s","version":"2.0.0",
                 "initialStats":{"SAVING":50,"HAPPINESS":50},
                 "sprites":{"MOM":{"neutral":"{{ASSET_BASE}}/a/abc.webp"}},
                 "scenes":[
                   {"id":"intro","type":"dialogue","background":"{{ASSET_BASE}}/a/bg1.webp","text":"Chào","nextSceneId":"%s"},
                   {"id":"choice-1","type":"choice","options":[{"id":"a","text":"Tiết kiệm","nextSceneId":"end"},{"id":"b","text":"Tiêu ngay","nextSceneId":"end"}]},
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
        AccountRole role = new AccountRole();  // PR B: service tra vai trong DB, không tin JWT
        role.setAccount(a);
        role.setRole(Role.ADMIN);
        role.setGrantedAt(Instant.now());
        accountRoleRepo.save(role);
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

        // PR C: chỉ phát hành nháp ĐÃ DUYỆT — nháp chưa duyệt thì 6005.
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6005));
        // Duyệt nhưng người phát hành chính là người sửa → 6007 (bốn mắt cả ở khâu phát hành).
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.PATCH, false);
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(6007));
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.PATCH, true);
        // Số hiệu do BE tính: bản đầu luôn 1.0.0 — gửi số khác → 6004.
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"v1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6004));

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
        // Nháp đã RELEASED không bị kéo về: sửa tiếp mở dòng nháp mới, revision nối tiếp.
        assertThat(draftRepo.findAll()).extracting(d -> d.getStatus() + "@" + d.getRevision())
                .containsExactlyInAnyOrder("RELEASED@1", "DRAFT@2");
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.PATCH, true);
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.0\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6004));
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0.1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.current").value(true))
                .andExpect(jsonPath("$.result.changeClass").value("PATCH"));
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
        // Bản đang chạy không phải bản mới nhất → không phát hành chồng lên (sẽ đánh rơi thay đổi của 1.0.1).
        mvc.perform(post("/api/studio/releases").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6006));
    }

    /** Thu hồi bản lỗi: lùi về bản cũ hơn, bản bị thu hồi không dùng lại được; số hiệu sau đó vẫn đi lên; chương mới = MAJOR. */
    @Test
    void recallRollsBackAndNewChapterIsMajor() throws Exception {
        var asAdmin = admin();
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(chapter("Bản 1", "choice-1"))).andExpect(status().isOk());
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.PATCH, true);
        // Bản đầu luôn 1.0.0, loại PATCH vẫn ghi MAJOR vì CH01 là chương mới.
        mvc.perform(post("/api/studio/releases").with(asAdmin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.changeClass").value("MAJOR"));
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(chapter("Bản lỗi", "choice-1"))).andExpect(status().isOk());
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.MINOR, true);
        mvc.perform(post("/api/studio/releases").with(asAdmin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.result.version").value("1.1.0"));

        // Thu hồi: phải có lý do; chỉ bản đang chạy.
        mvc.perform(post("/api/studio/releases/1.1.0/recall").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1001));
        mvc.perform(post("/api/studio/releases/1.0.0/recall").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6006));
        mvc.perform(post("/api/studio/releases/1.1.0/recall").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"sai số liệu cảnh 2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.current").value(true));
        mvc.perform(get("/api/content/current")).andExpect(jsonPath("$.result.version").value("1.0.0"));
        assertThat(releaseRepo.findByVersion("1.1.0").orElseThrow().getRecallReason()).isEqualTo("sai số liệu cảnh 2");
        // Bản đã thu hồi không đặt lại được; không còn bản cũ hơn để lùi → từ chối, game không bao giờ mất bản hiện tại.
        mvc.perform(post("/api/studio/releases/1.1.0/current").with(asAdmin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6006));
        mvc.perform(post("/api/studio/releases/1.0.0/recall").with(asAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6006));

        // Bản sửa đi lên từ số LỚN NHẤT (kể cả bản đã thu hồi) — không ra lại 1.1.0. Thêm CH02 (khai PATCH) → MAJOR 2.0.0,
        // CH01 giữ nội dung của bản đang chạy 1.0.0.
        mvc.perform(put("/api/studio/chapters/CH02/draft").with(asAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(chapter("Chương 2", "choice-1"))).andExpect(status().isOk());
        ContentTestSupport.approve(repos(), "CH02", ChangeClass.PATCH, true);
        mvc.perform(post("/api/studio/releases").with(asAdmin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("2.0.0"))
                .andExpect(jsonPath("$.result.chapters.length()").value(2));
        mvc.perform(get("/api/content/releases/2.0.0/chapters/CH01")).andExpect(jsonPath("$.result.title").value("Bản 1"));
        mvc.perform(get("/api/studio/releases").with(asAdmin))
                .andExpect(jsonPath("$.result[0].version").value("2.0.0"))
                .andExpect(jsonPath("$.result[1].recalledAt").isNotEmpty());
    }

    /** Đọc nháp kèm nội dung (Reviewer chơi thử, Editor sửa tiếp) và xem trước bản sắp phát hành. */
    @Test
    void draftContentRoundTripAndReleasePreview() throws Exception {
        var asAdmin = admin();
        mvc.perform(get("/api/studio/chapters/CH01/draft").with(asAdmin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(1003));
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(chapter("Nháp", "choice-1"))).andExpect(status().isOk());

        // Tải về: URL thật để chơi thử; lưu lại nguyên vậy thì BE đổi về placeholder.
        String body = mvc.perform(get("/api/studio/chapters/ch01/draft").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.draft.status").value("DRAFT"))
                .andExpect(jsonPath("$.result.draft.revision").value(1))
                .andExpect(jsonPath("$.result.content.scenes[0].background").value("https://cdn.test.local/a/bg1.webp"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String content = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("result").path("content").toString();
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(asAdmin)
                .contentType(MediaType.APPLICATION_JSON).content(content)).andExpect(status().isOk());
        assertThat(draftRepo.findAll()).singleElement().satisfies(d -> {
            assertThat(d.getContent()).contains("{{ASSET_BASE}}/a/bg1.webp").doesNotContain("cdn.test.local");
            assertThat(d.getRevision()).isEqualTo(2);
        });

        // Xem trước: chạy đúng luật publish, không ghi gì.
        mvc.perform(get("/api/studio/releases/next").with(asAdmin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6005));
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.PATCH, false);
        mvc.perform(get("/api/studio/releases/next").with(asAdmin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(6007));
        ContentTestSupport.approve(repos(), "CH01", ChangeClass.PATCH, true);
        mvc.perform(get("/api/studio/releases/next").with(asAdmin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.changeClass").value("MAJOR"))
                .andExpect(jsonPath("$.result.currentVersion").doesNotExist())
                .andExpect(jsonPath("$.result.drafts[0].code").value("CH01"));
        assertThat(releaseRepo.count()).isZero();
    }

    /**
     * WF-5 / CNT-3 end to end with the REAL roles, approval through the review API (no repository shortcut):
     * GAME_EDITOR saves + submits → GAME_REVIEWER approves → GAME_MANAGER publishes → second change → manager
     * publishes it → manager recalls it and the game falls back to the first release.
     */
    @Test
    void editorReviewerManagerPublishThenRecall() throws Exception {
        var editor = withRole(Role.GAME_EDITOR);
        var reviewer = withRole(Role.GAME_REVIEWER);
        var manager = withRole(Role.GAME_MANAGER);

        mvc.perform(put("/api/studio/chapters/CH01/draft").with(editor)
                .contentType(MediaType.APPLICATION_JSON).content(chapter("Bản 1", "choice-1"))).andExpect(status().isOk());
        mvc.perform(post("/api/studio/chapters/CH01/submit").with(editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeClass\":\"MINOR\",\"note\":\"chuong moi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("SUBMITTED"));
        // Not approved yet: the manager has nothing to publish.
        mvc.perform(post("/api/studio/releases").with(manager).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(6005));
        mvc.perform(post("/api/studio/chapters/CH01/review").with(reviewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":true,\"changeClass\":null,\"note\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("APPROVED"));

        mvc.perform(post("/api/studio/releases").with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"ban dau\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.current").value(true));
        mvc.perform(get("/api/content/releases/1.0.0/chapters/CH01")).andExpect(jsonPath("$.result.title").value("Bản 1"));
        assertThat(draftRepo.findAll()).extracting(d -> d.getStatus().name()).containsExactly("RELEASED");

        // Second round: a faulty change goes through the same three hands.
        mvc.perform(put("/api/studio/chapters/CH01/draft").with(editor)
                .contentType(MediaType.APPLICATION_JSON).content(chapter("Bản lỗi", "choice-1"))).andExpect(status().isOk());
        mvc.perform(post("/api/studio/chapters/CH01/submit").with(editor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeClass\":\"PATCH\",\"note\":\"sua chu\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/studio/chapters/CH01/review").with(reviewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pass\":true,\"changeClass\":\"PATCH\",\"note\":null}")).andExpect(status().isOk());
        mvc.perform(post("/api/studio/releases").with(manager).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.1"))
                .andExpect(jsonPath("$.result.changeClass").value("PATCH"));
        mvc.perform(get("/api/content/current")).andExpect(jsonPath("$.result.version").value("1.0.1"));

        // Only the manager recalls (editor / reviewer → 3004); recall falls back to 1.0.0.
        mvc.perform(post("/api/studio/releases/1.0.1/recall").with(editor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/studio/releases/1.0.1/recall").with(reviewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/studio/releases/1.0.1/recall").with(manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"loi chu canh 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.version").value("1.0.0"))
                .andExpect(jsonPath("$.result.current").value(true));
        mvc.perform(get("/api/content/current")).andExpect(jsonPath("$.result.version").value("1.0.0"));
        mvc.perform(get("/api/content/chapters/CH01")).andExpect(jsonPath("$.result.title").value("Bản 1"));
        assertThat(releaseRepo.findByVersion("1.0.1").orElseThrow().getRecallReason()).isEqualTo("loi chu canh 1");
    }

    /** An account holding one Studio role, recorded in account_role (services read roles from the DB). */
    private RequestPostProcessor withRole(Role r) {
        Account a = new Account();
        a.setEmail(r.name().toLowerCase() + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(r.name());
        a.setCreatedAt(Instant.now());
        a = accountRepo.save(a);
        AccountRole role = new AccountRole();
        role.setAccount(a);
        role.setRole(r);
        role.setGrantedAt(Instant.now());
        accountRoleRepo.save(role);
        String id = a.getId().toString();
        return jwt().jwt(j -> j.subject(id)).authorities(new SimpleGrantedAuthority("SCOPE_" + r.name()));
    }

    private ContentTestSupport.Repos repos() {
        return new ContentTestSupport.Repos(releaseChapterRepo, releaseRepo, draftRepo, chapterRepo);
    }
}
