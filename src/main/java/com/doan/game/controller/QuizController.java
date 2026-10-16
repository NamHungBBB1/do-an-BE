package com.doan.game.controller;

import com.doan.game.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @PostMapping("/api/admin/questions/import")
    public void importQuestions() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/api/questions")
    public void listQuestions() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/api/quizzes")
    public void createQuiz() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/api/quizzes")
    public void listQuizzes() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/api/quizzes/{id}")
    public void getQuiz() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PutMapping("/api/quizzes/{id}")
    public void updateQuiz() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @DeleteMapping("/api/quizzes/{id}")
    public void deleteQuiz() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/api/quizzes/{id}/publish")
    public void publish() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/api/quizzes/{id}/results")
    public void getClassResults() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @GetMapping("/api/quizzes/{id}/results/{slotId}")
    public void getSlotResult() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
