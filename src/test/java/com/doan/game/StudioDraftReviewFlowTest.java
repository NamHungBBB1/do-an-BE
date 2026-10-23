package com.doan.game;

import com.doan.game.entity.Account;
import com.doan.game.entity.AccountRole;
import com.doan.game.enums.Role;
import com.doan.game.repository.AccountRepository;
import com.doan.game.repository.AccountRoleRepository;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Studio PR B (21/10): vòng đời nháp chương theo ba vai, qua HTTP thật (MockMvc + H2).
 * Editor lưu nháp → gửi duyệt (đề xuất loại PATCH / MINOR / MAJOR) → Reviewer trả về kèm lý do hoặc duyệt
 * (xác nhận loại); không ai duyệt bản mình sửa, kể cả ADMIN (6002); nháp đang chờ duyệt thì không lưu đè
 * được (6003); sửa nháp đã duyệt thì phiếu duyệt hết giá trị, về DRAFT. Vai thật ghi vào account_role vì
 * service tra DB. Dùng chương CH42 riêng và dọn sau mỗi ca: publish() của test khác gom MỌI nháp vào bản.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true")
@AutoConfigureMockMvc
class StudioDraftReviewFlowTest {

    private static final String CODE = "CH42";
    private static final String BASE = "/api/studio/chapters/" + CODE;

    @Autowired MockMvc mvc;
    @Autowired AccountRepository accountRepo;
    @Autowired AccountRoleRepository accountRoleRepo;
    @Autowired ChapterRepository chapterRepo;
    @Autowired ChapterDraftRepository draftRepo;

    @AfterEach
    void removeTestChapter() {
        chapterRepo.findByCode(CODE).ifPresent(c -> {
            draftRepo.deleteAll(draftRepo.findAll().stream()
                    .filter(d -> d.getChapter().getId().equals(c.getId())).toList());
            chapterRepo.delete(c);
        });
    }

    @Test
    void editorSubmitsReviewerReturnsThenApproves() throws Exception {
        RequestPostProcessor lan = as(withRole(account("lan"), Role.GAME_EDITOR), "SCOPE_GAME_EDITOR");
        Account namAccount = withRole(account("nam"), Role.GAME_REVIEWER);
        RequestPostProcessor nam = as(namAccount, "SCOPE_GAME_REVIEWER");
        RequestPostProcessor manager = as(withRole(account("manager"), Role.GAME_MANAGER), "SCOPE_GAME_MANAGER");

        saveDraft(lan, "Ban 1").andExpect(status().isOk());
        submit(lan, "MINOR", "them canh giai thich")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.result.changeClass").value("MINOR"))
                .andExpect(jsonPath("$.result.changeNote").value("them canh giai thich"))
                .andExpect(jsonPath("$.result.submittedAt").isNotEmpty());

        // Đang chờ duyệt: không lưu đè, không gửi lại.
        saveDraft(lan, "Ban 1b").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6003));
        submit(lan, "MINOR", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6003));

        // Trả về phải có lý do.
        review(nam, false, null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        review(nam, false, null, "so lieu canh 3 sai")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("REJECTED"))
                .andExpect(jsonPath("$.result.reviewerId").value(namAccount.getId().toString()))
                .andExpect(jsonPath("$.result.reviewNote").value("so lieu canh 3 sai"));

        // Bị trả về thì phải sửa (về DRAFT) rồi mới gửi lại.
        submit(lan, "MINOR", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(6003));
        saveDraft(lan, "Ban 2").andExpect(status().isOk());
        submit(lan, "PATCH", "sua so lieu").andExpect(status().isOk());

        // Rút lại khi còn chờ duyệt, rồi gửi lại.
        mvc.perform(post(BASE + "/withdraw").with(lan))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("DRAFT"));
        submit(lan, "PATCH", "sua so lieu").andExpect(status().isOk());

        // Reviewer xác nhận loại: nghi thì xếp cao hơn.
        review(nam, true, "MINOR", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("APPROVED"))
                .andExpect(jsonPath("$.result.changeClass").value("MINOR"))
                .andExpect(jsonPath("$.result.revision").value(2))
                .andExpect(jsonPath("$.result.reviewedRevision").value(2));

        // Manager (và mọi vai Studio) xem được danh sách nháp.
        mvc.perform(get("/api/studio/chapters").with(manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.code == '" + CODE + "')].status").value("APPROVED"));

        // Sửa nháp đã duyệt: phiếu duyệt hết giá trị, về DRAFT.
        saveDraft(lan, "Ban 3").andExpect(status().isOk());
        mvc.perform(get("/api/studio/chapters").with(nam))
                .andExpect(jsonPath("$.result[?(@.code == '" + CODE + "')].status").value("DRAFT"))
                .andExpect(jsonPath("$.result[?(@.code == '" + CODE + "')].revision").value(3));
    }

    @Test
    void selfReviewAndWrongRolesAreRefused() throws Exception {
        RequestPostProcessor admin = as(withRole(account("admin"), Role.ADMIN), "SCOPE_ADMIN");
        RequestPostProcessor lan = as(withRole(account("lan"), Role.GAME_EDITOR), "SCOPE_GAME_EDITOR");
        RequestPostProcessor nam = as(withRole(account("nam"), Role.GAME_REVIEWER), "SCOPE_GAME_REVIEWER");
        RequestPostProcessor manager = as(withRole(account("manager"), Role.GAME_MANAGER), "SCOPE_GAME_MANAGER");

        // ADMIN sửa và gửi được, nhưng không tự duyệt bản mình sửa.
        saveDraft(admin, "Ban admin").andExpect(status().isOk());
        submit(admin, null, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(1001));
        submit(admin, "PATCH", null).andExpect(status().isOk());
        review(admin, true, null, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(6002));

        // Sai vai: Reviewer / Manager không sửa nháp, Editor / Manager không duyệt, chỉ người sửa mới rút.
        saveDraft(nam, "Reviewer sua").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        saveDraft(manager, "Manager sua").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        review(lan, true, null, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        review(manager, true, null, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post(BASE + "/withdraw").with(lan))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));

        // Phát hành / đổi bản hiện tại: chỉ Manager hoặc ADMIN. Manager qua được vai, vướng luật phát hành → 409
        // (6005 không có nháp duyệt, hoặc 6004 số hiệu lệch — tuỳ class test chạy trước để lại gì trong H2).
        String badRelease = "{\"version\":\"x\"}";
        mvc.perform(post("/api/studio/releases").with(lan).contentType(MediaType.APPLICATION_JSON).content(badRelease))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/studio/releases").with(nam).contentType(MediaType.APPLICATION_JSON).content(badRelease))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/studio/releases/9.9.9/current").with(lan))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(3004));
        mvc.perform(post("/api/studio/releases").with(manager).contentType(MediaType.APPLICATION_JSON).content(badRelease))
                .andExpect(status().isConflict());

        // Người lớn thường không qua cổng Studio.
        mvc.perform(get("/api/studio/chapters").with(as(account("parent"), "SCOPE_PARENT")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers
    private ResultActions saveDraft(RequestPostProcessor who, String title) throws Exception {
        return mvc.perform(put(BASE + "/draft").with(who).contentType(MediaType.APPLICATION_JSON).content("""
                {"id":"chapter-42","title":"%s",
                 "scenes":[{"id":"intro","type":"dialogue","text":"Chao","nextSceneId":"end"},{"id":"end","type":"ending"}]}
                """.formatted(title)));
    }

    private ResultActions submit(RequestPostProcessor who, String changeClass, String note) throws Exception {
        return mvc.perform(post(BASE + "/submit").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"changeClass\":" + quote(changeClass) + ",\"note\":" + quote(note) + "}"));
    }

    private ResultActions review(RequestPostProcessor who, Boolean pass, String changeClass, String note)
            throws Exception {
        return mvc.perform(post(BASE + "/review").with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pass\":" + pass + ",\"changeClass\":" + quote(changeClass)
                        + ",\"note\":" + quote(note) + "}"));
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private Account account(String prefix) {
        Account a = new Account();
        a.setEmail(prefix + "-" + System.nanoTime() + "@test.local");
        a.setDisplayName(prefix);
        a.setCreatedAt(Instant.now());
        return accountRepo.save(a);
    }

    private Account withRole(Account a, Role role) {
        AccountRole r = new AccountRole();
        r.setAccount(a);
        r.setRole(role);
        r.setGrantedAt(Instant.now());
        accountRoleRepo.save(r);
        return a;
    }

    /** JWT giả: sub = id tài khoản, authorities đặt thẳng như SecurityConfig suy ra. */
    private static RequestPostProcessor as(Account a, String... authorities) {
        return jwt().jwt(j -> j.subject(a.getId().toString()))
                .authorities(Arrays.stream(authorities).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }
}
