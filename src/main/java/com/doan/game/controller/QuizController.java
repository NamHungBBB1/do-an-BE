package com.doan.game.controller;

import com.doan.game.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Cửa vào HTTP. Tầng này MỎNG: nhận, gọi service, trả về. Không nghiệp vụ.
 */
@RestController
@RequestMapping("/api/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @PostMapping
    public void soanQuiz() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/publish")
    public void phatHanh() {
        throw new UnsupportedOperationException("chua cai dat");
    }

    @PostMapping("/{id}/submit")
    public void nopBai() {
        throw new UnsupportedOperationException("chua cai dat");
    }

}
