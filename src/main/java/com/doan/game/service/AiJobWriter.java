package com.doan.game.service;

import com.doan.game.entity.AiJob;

/**
 * Chỗ cắm tầng AI cho hàng chờ AiJob (spec kho ảnh + giọng 4.7b; khung báo cáo AI 13.3). PR này CHƯA có cài đặt nào: đợt D
 * cắm vào đây lời gọi Gemini (xoay khoá AiKey, BR-34), kiểm đầu ra (khung mục 4 ý 3) và tầng 2. Hàng chờ (AiJobQueue) lo phần
 * chung: khoá việc, đếm lần thử, lùi thời gian, hết lượt, việc kẹt, chép kết quả về DailySummary.
 */
public interface AiJobWriter {

    /**
     * Kết quả đã qua kiểm của BE. text: với REPORT_SUMMARY là bài hiện cho người lớn. tier 1–2 (tầng 3–4 nộp qua phiếu
     * Discord, đợt D); keyAlias = tên gợi nhớ của khoá đã viết ra kết quả, KHÔNG bao giờ là khoá.
     */
    record Output(String text, int tier, String provider, String keyAlias, String model, String promptVersion) {
    }

    /** Báo trạng thái sống trong lúc gọi (trang admin "Nhật ký việc AI"). */
    interface Progress {
        /** Đang thử khoá này — chỉ tên gợi nhớ. */
        void usingKey(String keyAlias);
    }

    /**
     * Viết một việc đang RUNNING. Lỗi: ném {@link AiTextClient.AiCallException} — quota() = true khi mọi khoá hết lượt
     * (429 / 402): hẹn lại, KHÔNG tính lần thử; quota() = false (5xx, timeout, đầu ra bị BE loại): tính lần thử. Ngoại lệ
     * khác cũng tính là lỗi thường.
     */
    Output write(AiJob job, Progress progress);
}
