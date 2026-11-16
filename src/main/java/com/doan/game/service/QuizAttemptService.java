package com.doan.game.service;

import com.doan.game.DTO.request.SaveAnswerRequest;
import com.doan.game.DTO.request.SubmitAttemptRequest;
import com.doan.game.DTO.response.AttemptResponse;
import com.doan.game.DTO.response.AttemptReviewResponse;

import java.util.UUID;

/**
 * Lượt làm quiz v3 (build/thiet-ke-be-quiz-v3-2026-11-16.md mục 4): mỗi em một đề riêng đã xáo (lưu paper), câu trả lời tự
 * lưu, đồng hồ do BE tính, chấm theo paper rồi đổi về chỉ số gốc. Lần nộp đầu của (đề, em) ghi QuizResult / QuizAnswer — kết
 * quả chính thức không đổi đường đọc. Lượt quá deadline + thời gian trễ được chốt lười ở mọi đường đọc / ghi lượt và khi
 * giáo viên xem kết quả. Đáp án đúng không bao giờ xuống trẻ.
 *
 * Bảng phụ trách: QuizAttempt
 */
public interface QuizAttemptService {

    AttemptResponse open(UUID slotId, UUID quizId);

    void saveAnswer(UUID slotId, UUID attemptId, int pos, SaveAnswerRequest req);

    AttemptReviewResponse submit(UUID slotId, UUID attemptId, SubmitAttemptRequest req);

    AttemptReviewResponse get(UUID slotId, UUID attemptId);

    /** Giáo viên đọc kết quả: chốt mọi lượt quá giờ của đề (một truy vấn lọc, khoá slot liên quan). */
    void finalizeExpired(UUID quizId);

    /**
     * Chốt mọi lượt quá giờ trong các đề của một nhóm, giao dịch RIÊNG (REQUIRES_NEW) — gọi ở đầu báo cáo slot / nhóm, danh
     * sách đề giáo viên, chi tiết đề, danh sách của em (đường đọc readOnly không ghi được).
     */
    void finalizeExpiredInGroup(UUID groupId);
}
