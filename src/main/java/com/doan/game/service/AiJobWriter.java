package com.doan.game.service;

import com.doan.game.entity.AiJob;

/**
 * Chỗ cắm tầng AI cho hàng chờ AiJob (spec kho ảnh + giọng 4.7b; khung báo cáo AI 13.3). Cài đặt: ReportAiWriter (đợt D —
 * Gemini xoay khoá, OpenRouter tầng 2, kiểm đầu ra, AiCallLog). Hàng chờ (AiJobQueue) lo phần chung: khoá việc, đếm lần thử,
 * lùi thời gian, hết lượt, việc kẹt, chép kết quả về DailySummary.
 */
public interface AiJobWriter {

    /**
     * Kết quả đã qua kiểm của BE. text: với REPORT_SUMMARY là bài hiện cho người lớn. tier 1–2 (tầng 3–4 nộp qua phiếu
     * Discord, đợt D); keyAlias = tên gợi nhớ của khoá đã viết ra kết quả, KHÔNG bao giờ là khoá.
     */
    record Output(String text, int tier, String provider, String keyAlias, String model, String promptVersion,
                  String raw) {
        /** raw = đầu ra thô của AI (lưu AiJob.output); thiếu thì dùng text. */
        public Output(String text, int tier, String provider, String keyAlias, String model, String promptVersion) {
            this(text, tier, provider, keyAlias, model, promptVersion, text);
        }
    }

    /** Báo trạng thái sống trong lúc gọi (trang admin "Nhật ký việc AI"). */
    interface Progress {
        /** Đang thử khoá này — chỉ tên gợi nhớ. */
        void usingKey(String keyAlias);

        /** Lý do gửi của lô đang viết (FULL | TIMEOUT | SINGLE) — chỉ để ghi AiCallLog; không rõ thì null. */
        default String sendReason() {
            return null;
        }
    }

    /**
     * Viết một việc đang RUNNING. Lỗi: ném {@link AiTextClient.AiCallException} — quota() = true khi mọi khoá hết lượt
     * (429): hẹn lại, KHÔNG tính lần thử; quota() = false (5xx, timeout, đầu ra bị BE loại): tính lần thử; fatal() = FAILED
     * ngay với mã code() (SAFETY, đầu ra bị loại lần hai, không còn khoá). Ngoại lệ khác cũng tính là lỗi thường.
     */
    Output write(AiJob job, Progress progress);

    /**
     * Kết quả một lô (25/11, gom lô): ok = việc đã viết xong; errors = việc lỗi riêng (bài bị loại, thiếu trong kết quả…) —
     * hàng chờ tính lần thử, lần sau gửi lẻ; quota = việc phải hẹn lại vì mọi khoá hết lượt (không tính lần thử).
     */
    record BatchResult(java.util.Map<java.util.UUID, Output> ok,
                       java.util.Map<java.util.UUID, AiTextClient.AiCallException> errors,
                       java.util.List<java.util.UUID> quota) {
    }

    /**
     * Viết nhiều việc cùng ngăn trong MỘT lần gọi (gom lô). Không ném lỗi: mọi kết quả nằm trong BatchResult. Mặc định gọi lẻ
     * từng việc (writer không biết gom lô vẫn đúng); hết lượt thì việc đó và các việc sau vào quota.
     */
    default BatchResult writeBatch(java.util.List<AiJob> jobs, Progress progress) {
        java.util.Map<java.util.UUID, Output> ok = new java.util.LinkedHashMap<>();
        java.util.Map<java.util.UUID, AiTextClient.AiCallException> errors = new java.util.LinkedHashMap<>();
        java.util.List<java.util.UUID> quota = new java.util.ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            AiJob job = jobs.get(i);
            try {
                ok.put(job.getId(), write(job, progress));
            } catch (AiTextClient.AiCallException e) {
                if (e.quota()) {
                    jobs.subList(i, jobs.size()).forEach(j -> quota.add(j.getId()));
                    break;
                }
                errors.put(job.getId(), e);
            } catch (RuntimeException e) {
                errors.put(job.getId(), new AiTextClient.AiCallException(e.toString(), false, "AI_ERROR", false));
            }
        }
        return new BatchResult(ok, errors, quota);
    }
}
