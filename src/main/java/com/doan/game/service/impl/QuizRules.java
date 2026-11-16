package com.doan.game.service.impl;

/**
 * Hằng số nghiệp vụ của quiz v3 (build/thiet-ke-be-quiz-v3-2026-11-16.md mục 0): một chỗ duy nhất, không rải số trong code.
 * Thời gian trễ cho mạng là cấu hình vận hành (app.quiz.grace-seconds), không nằm ở đây.
 */
final class QuizRules {

    /** Một đề / một lô câu: 1..100 câu. */
    static final int MAX_QUESTIONS = 100;
    /** Một câu của giáo viên: 2..4 lựa chọn. */
    static final int MIN_OPTIONS = 2;
    static final int MAX_OPTIONS = 4;
    /** Độ dài sau trim. */
    static final int MAX_STEM_LENGTH = 500;
    static final int MAX_OPTION_LENGTH = 200;
    static final int MAX_TITLE_LENGTH = 120;
    /** Kiểm tra: không nhập thời gian thì 1 phút × số câu mỗi em; nhập thì 1..180 phút. */
    static final int DEFAULT_SECONDS_PER_QUESTION = 60;
    static final int MIN_DURATION_MINUTES = 1;
    static final int MAX_DURATION_MINUTES = 180;
    /** Mã lượt làm giáo viên thấy = bấy nhiêu ký tự đầu id lượt. */
    static final int ATTEMPT_CODE_LENGTH = 6;

    private QuizRules() {
    }
}
