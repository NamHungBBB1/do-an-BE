package com.doan.game.enums;

/**
 * Loại thông báo học tập cho người lớn (24/11/2026). Ba loại, không phải năm:
 * — POST_TEST_REQUIRED bỏ vì luôn bắn cùng lúc CHAPTER_COMPLETED, mà người làm hậu trắc là trẻ;
 * — POST_TEST_SUBMITTED gộp vào REPORT_READY vì nộp hậu trắc là có báo cáo ngay.
 */
public enum NotificationType {
    /** Trẻ hoàn thành một chương truyện. */
    CHAPTER_COMPLETED,
    /** Trẻ nộp một đề giáo viên giao. */
    QUIZ_SUBMITTED,
    /** Báo cáo chương của trẻ đã sẵn sàng (trẻ vừa nộp hậu trắc). */
    REPORT_READY
}
