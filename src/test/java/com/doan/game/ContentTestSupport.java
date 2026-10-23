package com.doan.game;

import com.doan.game.entity.ChapterDraft;
import com.doan.game.enums.ChangeClass;
import com.doan.game.enums.DraftStatus;
import com.doan.game.repository.ChapterDraftRepository;
import com.doan.game.repository.ChapterRepository;
import com.doan.game.repository.GameReleaseRepository;
import com.doan.game.repository.ReleaseChapterRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phát hành nội dung cho test KHÔNG thuộc Studio (play, quiz, report). Từ PR C (23/10) publish chỉ gom nháp đã duyệt
 * và BE tự tính số hiệu, nên các test này không còn tự đặt "1.0.2" / "1.0.3"… được nữa.
 *
 * Xoá sạch nội dung (như ContentFlowTest — mọi class dùng chung một H2, thứ tự class khác nhau giữa Linux và Windows),
 * lưu nháp qua API, đánh dấu APPROVED thẳng trong repo (bỏ khâu duyệt hai người — đã có test riêng ở
 * StudioDraftReviewFlowTest / StudioReleaseFlowTest), rồi phát hành không gửi version. Trả số hiệu BE tính (luôn 1.0.0).
 */
final class ContentTestSupport {

    private ContentTestSupport() {
    }

    record Repos(ReleaseChapterRepository releaseChapters, GameReleaseRepository releases,
                 ChapterDraftRepository drafts, ChapterRepository chapters) {

        void wipe() {
            releaseChapters.deleteAll();
            releases.deleteAll();
            drafts.deleteAll();
            chapters.deleteAll();
        }
    }

    /**
     * Đánh dấu nháp mới nhất của chương là APPROVED như Reviewer vừa duyệt. clearEditor: bỏ người sửa để người phát
     * hành không vướng luật "không tự phát hành nháp mình sửa" (6007).
     */
    static void approve(Repos repos, String code, ChangeClass cls, boolean clearEditor) {
        ChapterDraft d = repos.drafts().findTopByChapter_IdOrderByRevisionDesc(
                repos.chapters().findByCode(code).orElseThrow().getId()).orElseThrow();
        d.setStatus(DraftStatus.APPROVED);
        if (clearEditor) {
            d.setEditor(null);
        }
        d.setChangeClass(cls);
        d.setReviewedRevision(d.getRevision());
        repos.drafts().save(d);
    }

    static String publishFresh(MockMvc mvc, RequestPostProcessor admin, Repos repos, Map<String, String> chapters,
                               String note) throws Exception {
        repos.wipe();
        for (var e : chapters.entrySet()) {
            var res = mvc.perform(put("/api/studio/chapters/" + e.getKey() + "/draft").with(admin)
                    .contentType(MediaType.APPLICATION_JSON).content(e.getValue())).andReturn().getResponse();
            assertThat(res.getStatus()).as(res.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
            approve(repos, e.getKey(), ChangeClass.MAJOR, true);
        }
        var pub = mvc.perform(post("/api/studio/releases").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + note + "\"}")).andReturn().getResponse();
        String body = pub.getContentAsString(StandardCharsets.UTF_8);
        assertThat(pub.getStatus()).as(body).isEqualTo(200);
        return new ObjectMapper().readTree(body).path("result").path("version").asText();
    }
}
