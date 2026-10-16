package com.doan.game.service.impl;

import com.doan.game.service.QuizService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Quiz hai kiểu (quiz.md v2, Hưng chốt 16/10). Kho câu hỏi: admin nhập (upsert theo externalId); giáo viên chọn câu kho chung (chapter NULL) soạn đề CLASS_TEST cho một lớp, publish rồi bất biến. Hậu trắc: lớp bật cờ postTestEnabledAt thì khi em xong chương N, BE tự tạo đề POST_TEST (một đề / lớp / chương, chụp questionIds lúc tạo, không hạn nộp) và em thấy trong danh sách của mình. Mỗi em nộp một lần, máy chủ chấm, lưu từng câu; đáp án không bao giờ xuống trẻ.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {

    @Override
    public ImportQuestionsResponse importQuestions(java.util.List<ImportQuestionDto> questions) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public java.util.List<QuestionResponse> listQuestions(String standard, String concept, String pool, String q) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizResponse createQuiz(UUID callerId, CreateQuizRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public java.util.List<QuizResponse> listQuizzes(UUID callerId, UUID groupId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizDetailResponse getQuiz(UUID callerId, UUID quizId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizResponse updateQuiz(UUID callerId, UUID quizId, UpdateQuizRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void deleteQuiz(UUID callerId, UUID quizId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizResponse publish(UUID callerId, UUID quizId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizResultsResponse getClassResults(UUID callerId, UUID quizId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizSlotResultResponse getSlotResult(UUID callerId, UUID quizId, UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public LearnerQuizListResponse listForLearner(UUID slotId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public LearnerQuizResponse getForLearner(UUID slotId, UUID quizId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public SubmitQuizResponse submitQuiz(UUID slotId, UUID quizId, SubmitQuizRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
