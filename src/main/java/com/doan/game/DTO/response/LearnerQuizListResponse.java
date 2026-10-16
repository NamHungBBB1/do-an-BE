package com.doan.game.DTO.response;

/** GET /api/learner/quizzes: đề giáo viên đã publish của lớp + hậu trắc của những chương em đã xong (lớp bật cờ). FAMILY → hai mảng rỗng. */
public record LearnerQuizListResponse(java.util.List<LearnerQuizPendingResponse> pending, java.util.List<LearnerQuizCompletedResponse> completed) {
}
