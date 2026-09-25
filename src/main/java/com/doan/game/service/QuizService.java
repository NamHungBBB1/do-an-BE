package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Kho câu hỏi dùng chung, soạn quiz và đọc kết quả.
 *
 * Bảng phụ trách: Question, Quiz, QuizResult
 */
public interface QuizService {

    QuizResponse soanQuiz(UUID ownerId, CreateQuizRequest req);

    void phatHanh(UUID quizId);

    QuizResultResponse nopBai(UUID quizId, UUID slotId, SubmitQuizRequest req);

}
