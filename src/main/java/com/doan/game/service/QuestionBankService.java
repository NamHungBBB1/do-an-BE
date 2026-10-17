package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Kho câu hỏi trong Studio cho ba vai nội bộ (quiz.md v2, Hưng chốt 17/10, cách 1): xem toàn kho kể cả bộ hậu trắc và câu đã rút; thêm câu (externalId tự sinh nếu trống; replacesId → câu cũ bị rút cùng giao dịch); sửa — câu đã nằm trong đề đã phát thì khoá chữ / lựa chọn / đáp án (8009), chỉ sửa nhãn; rút / đưa lại. Không xoá cứng. Editor, Manager, Admin ghi; Reviewer chỉ đọc (cổng SecurityConfig).
 *
 * Bảng phụ trách: Question
 */
public interface QuestionBankService {

    java.util.List<QuestionResponse> list(Integer chapter, String pool, String standard, String concept, String q, boolean includeRetired);

    QuestionResponse create(UUID callerId, UpsertQuestionRequest req);

    QuestionResponse update(UUID callerId, UUID questionId, UpsertQuestionRequest req);

    QuestionResponse retire(UUID callerId, UUID questionId);

    QuestionResponse restore(UUID callerId, UUID questionId);

}
