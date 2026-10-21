package com.doan.game.service;

import com.doan.game.DTO.request.CreateReleaseRequest;
import com.doan.game.DTO.request.ReviewDraftRequest;
import com.doan.game.DTO.request.SubmitDraftRequest;
import com.doan.game.DTO.response.ChapterSummaryResponse;
import com.doan.game.DTO.response.DraftSummaryResponse;
import com.doan.game.DTO.response.ReleaseSummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Nội dung game — nửa PHÁT của Studio (Hưng chốt 08–09/10): mỗi chương là một cục JSON đúng hình dạng FE
 * đang chạy (chapterNV2VisualNovel.js); bản phát hành đóng băng theo phiên bản SemVer; đọc công khai.
 *
 * Giai đoạn 2, PR B (21/10): vòng đời nháp theo vai Editor / Reviewer / Manager (quy tắc phát hành 05/10). ADMIN làm
 * được mọi vai nhưng vẫn không tự duyệt bản mình sửa. publish() CHƯA lọc nháp đã duyệt — việc của PR C.
 */
public interface ContentService {

    /** Bản phát hành đang dùng + danh sách chương. 1003 nếu chưa phát hành lần nào. */
    ReleaseSummaryResponse currentRelease();

    List<ReleaseSummaryResponse> listReleases();

    /** JSON chương trong một phiên bản, {{ASSET_BASE}} đã thay bằng tiền tố kho file. 1003 nếu không có. */
    JsonNode releaseChapter(String version, String code);

    /** Như trên nhưng theo bản hiện tại. */
    JsonNode currentChapter(String code);

    /**
     * Editor / ADMIN lưu nháp cho chương (tạo Chapter nếu chưa có). Kiểm cấu trúc: scene id không trùng, mọi next trỏ
     * cảnh có thật. Nháp đang SUBMITTED thì 6003; nháp REJECTED / APPROVED lưu lại thì về DRAFT, revision tăng.
     */
    ChapterSummaryResponse saveDraft(UUID callerId, String code, JsonNode content);

    /** Bản nháp mới nhất của mọi chương, theo số chương (mọi vai Studio xem được). */
    List<DraftSummaryResponse> listDrafts();

    /** Editor / ADMIN gửi duyệt: DRAFT → SUBMITTED, kèm loại đề xuất. */
    DraftSummaryResponse submitDraft(UUID callerId, String code, SubmitDraftRequest req);

    /** Chính người sửa rút lại: SUBMITTED → DRAFT. */
    DraftSummaryResponse withdrawDraft(UUID callerId, String code);

    /** Reviewer / ADMIN, không phải người sửa (6002): SUBMITTED → APPROVED hoặc REJECTED (kèm lý do). */
    DraftSummaryResponse reviewDraft(UUID callerId, String code, ReviewDraftRequest req);

    /** Manager / ADMIN: gom bản nháp mới nhất của mọi chương thành phiên bản mới và đặt làm hiện tại. */
    ReleaseSummaryResponse publish(UUID callerId, CreateReleaseRequest req);

    /** Manager / ADMIN lùi / tiến bản: chỉ đổi con trỏ isCurrent, không xoá gì. */
    ReleaseSummaryResponse setCurrent(UUID callerId, String version);
}
