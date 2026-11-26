package com.doan.game.service;

import java.util.Map;

/**
 * Nội dung một tin push. Tách khỏi {@link PushService} để chỗ gọi không phụ thuộc SDK Firebase.
 *
 * {@code data} là phần app đọc khi người dùng bấm vào tin:
 *   notificationId — chống trùng, FCM có thể gửi lặp cùng một tin
 *   type           — CHAPTER_COMPLETED | QUIZ_SUBMITTED | REPORT_READY
 *   actionType     — OPEN_LEARNER_REPORT | OPEN_GROUP_REPORT | OPEN_QUIZ_RESULTS | OPEN_LEARNER_LIST
 *   resourceId     — id tương ứng với actionType (slotId / groupId / quizId)
 *
 * Giá trị FCM yêu cầu là chuỗi, nên map chỉ nhận String.
 */
public record PushMessage(String title, String body, Map<String, String> data) {

    public PushMessage {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
