package com.doan.game.service;

import com.doan.game.DTO.request.CreateReleaseRequest;
import com.doan.game.DTO.request.RecallReleaseRequest;
import com.doan.game.DTO.request.ReviewDraftRequest;
import com.doan.game.DTO.request.SubmitDraftRequest;
import com.doan.game.DTO.response.ChapterSummaryResponse;
import com.doan.game.DTO.response.DraftContentResponse;
import com.doan.game.DTO.response.ReleasePlanResponse;
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
 * được mọi vai nhưng vẫn không tự duyệt bản mình sửa. PR C (23/10): publish chỉ gom nháp đã duyệt, BE tự tính số hiệu,
 * thu hồi bản lỗi.
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

    /** Nháp mới nhất của một chương kèm JSON ({{ASSET_BASE}} đã thay) — Editor sửa tiếp, Reviewer chơi thử. 1003 nếu chưa có. */
    DraftContentResponse draftContent(String code);

    /**
     * Xem trước bản sắp phát hành cho người đang xem: chạy đúng luật của publish nhưng không ghi gì — lỗi giống hệt
     * publish (6005 không có nháp duyệt, 6006 bản đang chạy không phải bản mới nhất, 6007 người xem sửa một nháp trong bản).
     */
    ReleasePlanResponse previewRelease(UUID callerId);

    /** Bản nháp mới nhất của mọi chương, theo số chương (mọi vai Studio xem được). */
    List<DraftSummaryResponse> listDrafts();

    /** Editor / ADMIN gửi duyệt: DRAFT → SUBMITTED, kèm loại đề xuất. */
    DraftSummaryResponse submitDraft(UUID callerId, String code, SubmitDraftRequest req);

    /** Chính người sửa rút lại: SUBMITTED → DRAFT. */
    DraftSummaryResponse withdrawDraft(UUID callerId, String code);

    /** Reviewer / ADMIN, không phải người sửa (6002): SUBMITTED → APPROVED hoặc REJECTED (kèm lý do). */
    DraftSummaryResponse reviewDraft(UUID callerId, String code, ReviewDraftRequest req);

    /**
     * Manager / ADMIN: gom nháp APPROVED (không có → 6005; nháp do chính mình sửa → 6007) cùng các chương còn lại của
     * bản hiện tại thành phiên bản mới, đặt làm hiện tại; nháp đã vào bản → RELEASED. Số hiệu tính từ loại cao nhất
     * (chương mới = MAJOR) trên bản lớn nhất từng có; client gửi version lệch → 6004.
     */
    ReleaseSummaryResponse publish(UUID callerId, CreateReleaseRequest req);

    /** Manager / ADMIN lùi / tiến bản: chỉ đổi con trỏ isCurrent, không xoá gì. */
    ReleaseSummaryResponse setCurrent(UUID callerId, String version);

    /**
     * Manager / ADMIN thu hồi bản ĐANG CHẠY (lý do bắt buộc): lùi về bản cũ hơn chưa thu hồi, mới nhất; không còn bản
     * nào → 6006. Bản đã thu hồi không đặt lại được. Trả bản hiện tại mới.
     */
    ReleaseSummaryResponse recall(UUID callerId, String version, RecallReleaseRequest req);
}
