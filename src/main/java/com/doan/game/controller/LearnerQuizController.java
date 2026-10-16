package com.doan.game.controller;

import com.doan.game.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/learner/quizzes")
@RequiredArgsConstructor
public class LearnerQuizController {

    private final QuizService quizService;

    @GetMapping
    public void listForLearner() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/{id}")
    public void getForLearner() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/submit")
    public void submitQuiz() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
