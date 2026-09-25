package com.doan.game.service.impl;

import com.doan.game.service.QuizService;
import com.doan.game.DTO.request.*;
import com.doan.game.DTO.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Kho câu hỏi dùng chung, soạn quiz và đọc kết quả.
 *
 * KHUNG — chưa có nghiệp vụ. Mọi hàm còn ném UnsupportedOperationException
 * để không ai vô tình dùng một lớp rỗng mà tưởng nó chạy.
 */
@Service
@RequiredArgsConstructor
public class QuizServiceImpl implements QuizService {

    @Override
    public QuizResponse soanQuiz(UUID ownerId, CreateQuizRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public void phatHanh(UUID quizId) {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @Override
    public QuizResultResponse nopBai(UUID quizId, UUID slotId, SubmitQuizRequest req) {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
