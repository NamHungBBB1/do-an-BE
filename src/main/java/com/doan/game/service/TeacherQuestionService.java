package com.doan.game.service;

import com.doan.game.DTO.request.NewQuestionDto;
import com.doan.game.DTO.response.QuestionResponse;
import com.doan.game.DTO.response.SavedQuestionResponse;

import java.util.List;
import java.util.UUID;

/**
 * "Câu của tôi" của giáo viên (quiz v3, build/thiet-ke-be-quiz-v3-2026-11-16.md mục 2): lưu lô câu soạn / import (câu trùng
 * với câu chưa rút của chính người đó thì dùng lại id), sửa (câu đã nằm trong đề đã phát → 8009), xoá (đã vào đề → chỉ ẩn),
 * sao câu kho chung thành câu của mình. Chỉ chủ câu thấy và dùng; mọi đường cần gói TEACHER còn hạn (3005).
 *
 * Bảng phụ trách: Question (ownerId khác null)
 */
public interface TeacherQuestionService {

    List<QuestionResponse> listMine(UUID callerId);

    /** Lưu lô 1–100 câu; lỗi trả theo vị trí (1001, result = [{index, field, message}]). Dùng chung cho tạo đề có newQuestions. */
    List<SavedQuestionResponse> saveMine(UUID callerId, List<NewQuestionDto> questions);

    QuestionResponse updateMine(UUID callerId, UUID questionId, NewQuestionDto req);

    void deleteMine(UUID callerId, UUID questionId);

    QuestionResponse copy(UUID callerId, UUID questionId);
}
