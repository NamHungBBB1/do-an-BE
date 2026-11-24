package com.doan.game.service;

import com.doan.game.DTO.response.NotificationListResponse;
import com.doan.game.DTO.response.NotificationReadAllResponse;
import com.doan.game.DTO.response.NotificationReadResponse;
import com.doan.game.DTO.response.UnreadCountResponse;
import com.doan.game.entity.LearnerSlot;
import com.doan.game.entity.Quiz;

import java.util.UUID;

/**
 * Chuông thông báo học tập cho người lớn (24/11/2026): phụ huynh nhận tin theo từng trẻ, giáo viên
 * nhận MỘT dòng gộp cho mỗi lớp + chương/đề (có em nộp thêm thì cập nhật dòng đó, không đẻ thêm).
 *
 * Ba chỗ móc sự kiện, đều gọi TRONG transaction nghiệp vụ: hoàn thành chương, nộp đề, nộp hậu trắc.
 * Không lưu tên trẻ trong DB — câu chữ ghép lúc đọc.
 */
public interface NotificationService {

    NotificationListResponse list(UUID accountId, int page, int size, boolean unreadOnly);

    UnreadCountResponse unreadCount(UUID accountId);

    /** Đánh dấu đã đọc, idempotent: đọc lại dòng đã đọc thì giữ readAt cũ. */
    NotificationReadResponse markRead(UUID accountId, UUID notificationId);

    NotificationReadAllResponse markAllRead(UUID accountId);

    /** Trẻ vừa xong một chương truyện (attemptIndex để lần chơi mới lại báo được lần nữa). */
    void notifyChapterCompleted(LearnerSlot slot, int chapter, int attemptIndex);

    /** Trẻ vừa nộp đề giáo viên giao (không phải hậu trắc). */
    void notifyQuizSubmitted(LearnerSlot slot, Quiz quiz);

    /** Trẻ vừa nộp hậu trắc → báo cáo chương của trẻ đã sẵn sàng. */
    void notifyReportReady(LearnerSlot slot, Quiz postTest);

    /** Nhận xét cả lớp của một chương vừa có (bài mới hoặc bản tóm tắt nhanh) → dòng gộp REPORT_READY của giáo viên (D2, 25/11). */
    void notifyClassCommentReady(com.doan.game.entity.LearnerGroup group, Integer chapter);
}
