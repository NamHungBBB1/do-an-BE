package com.doan.game.service;

import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;

import java.util.UUID;

/**
 * Quiz hai kiểu (quiz.md v2, Hưng chốt 16/10). Kho câu hỏi: admin nhập (upsert theo externalId); giáo viên chọn câu kho chung (chapter NULL) soạn đề CLASS_TEST cho một lớp, publish rồi bất biến. Hậu trắc: lớp bật cờ postTestEnabledAt thì khi em xong chương N, BE tự tạo đề POST_TEST (một đề / lớp / chương, chụp questionIds lúc tạo, không hạn nộp) và em thấy trong danh sách của mình. Mỗi em nộp một lần, máy chủ chấm, lưu từng câu; đáp án không bao giờ xuống trẻ.
 *
 * Bảng phụ trách: Question, Quiz, QuizResult, QuizAnswer
 */
public interface QuizService {

    ImportQuestionsResponse importQuestions(java.util.List<ImportQuestionDto> questions);

    /**
     * listQuestions cần biết ai gọi (SCOPE_TEACHER gói còn hạn, hoặc ADMIN) — thêm callerId so với
     * chữ ký sinh sẵn; service tự kiểm, không tin vai trong JWT.
     */
    java.util.List<QuestionResponse> listQuestions(UUID callerId, String standard, String concept, String pool, String q);

    QuizResponse createQuiz(UUID callerId, CreateQuizRequest req);

    java.util.List<QuizResponse> listQuizzes(UUID callerId, UUID groupId);

    QuizDetailResponse getQuiz(UUID callerId, UUID quizId);

    QuizResponse updateQuiz(UUID callerId, UUID quizId, UpdateQuizRequest req);

    void deleteQuiz(UUID callerId, UUID quizId);

    QuizResponse publish(UUID callerId, UUID quizId);

    QuizResultsResponse getClassResults(UUID callerId, UUID quizId);

    QuizSlotResultResponse getSlotResult(UUID callerId, UUID quizId, UUID slotId);

    LearnerQuizListResponse listForLearner(UUID slotId);

    LearnerQuizResponse getForLearner(UUID slotId, UUID quizId);

    SubmitQuizResponse submitQuiz(UUID slotId, UUID quizId, SubmitQuizRequest req);

}
