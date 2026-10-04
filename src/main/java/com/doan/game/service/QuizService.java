package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Kho câu hỏi chung, giáo viên tự chọn câu soạn quiz, phát cho một lớp (chỉ nhóm CLASS), mỗi em làm một lần, máy chủ chấm, lưu từng câu trả lời.
 *
 * Bảng phụ trách: Question, Quiz, QuizResult, QuizAnswer
 */
public interface QuizService {

    QuizResponse createQuiz(UUID ownerId, CreateQuizRequest req);

    void publish(UUID quizId);

    QuizResultResponse submitQuiz(UUID quizId, UUID slotId, SubmitQuizRequest req);

    java.util.List<QuizResultResponse> getClassResults(UUID quizId);

}
