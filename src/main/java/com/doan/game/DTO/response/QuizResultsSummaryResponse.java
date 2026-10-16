package com.doan.game.DTO.response;

/** assigned = số dòng rows; submitted = số QuizResult; averageScore trung bình score trên bài đã nộp, null khi 0. */
public record QuizResultsSummaryResponse(int assigned, int submitted, Double averageScore) {
}
