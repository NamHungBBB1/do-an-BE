package com.doan.game.service;

import com.doan.game.DTO.request.CreateReleaseRequest;
import com.doan.game.DTO.response.ChapterSummaryResponse;
import com.doan.game.DTO.response.ReleaseSummaryResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Nội dung game — nửa PHÁT của Studio (Hưng chốt 08–09/10): mỗi chương là một cục JSON đúng hình dạng FE
 * đang chạy (chapterNV2VisualNovel.js); bản phát hành đóng băng theo phiên bản SemVer; đọc công khai.
 *
 * Giai đoạn 1 (09/10): chưa có duyệt — admin lưu nháp rồi phát hành. Nháp → duyệt → phát hành theo vai
 * Editor / Reviewer / Manager làm ở giai đoạn sau, trên chính các bảng này.
 */
public interface ContentService {

    /** Bản phát hành đang dùng + danh sách chương. 1003 nếu chưa phát hành lần nào. */
    ReleaseSummaryResponse currentRelease();

    List<ReleaseSummaryResponse> listReleases();

    /** JSON chương trong một phiên bản, {{ASSET_BASE}} đã thay bằng tiền tố kho file. 1003 nếu không có. */
    JsonNode releaseChapter(String version, String code);

    /** Như trên nhưng theo bản hiện tại. */
    JsonNode currentChapter(String code);

    /** Admin lưu nháp cho chương (tạo Chapter nếu chưa có). Kiểm cấu trúc: scene id không trùng, mọi next trỏ cảnh có thật. */
    ChapterSummaryResponse saveDraft(UUID editorId, String code, JsonNode content);

    /** Gom bản nháp mới nhất của mọi chương thành phiên bản mới và đặt làm hiện tại. */
    ReleaseSummaryResponse publish(UUID adminId, CreateReleaseRequest req);

    /** Lùi / tiến bản: chỉ đổi con trỏ isCurrent, không xoá gì. */
    ReleaseSummaryResponse setCurrent(UUID adminId, String version);
}
