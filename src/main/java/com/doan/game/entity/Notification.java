package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một thông báo học tập cho người lớn (phụ huynh / giáo viên), 24/11/2026.
 *
 * KHÔNG lưu tên trẻ và không lưu sẵn title/message: xoá slot là xoá tên thật (Luật BVDLCN 2025), nên
 * câu chữ được ghép lúc đọc từ slot hiện tại — slot đã xoá thì trả tên null. Cũng không lưu cột đếm
 * tiến độ: dòng gộp của giáo viên ("12/40 em đã nộp") đếm lúc đọc để không lệch khi có em bị xoá.
 *
 * Dòng của phụ huynh gắn một slot; dòng gộp của giáo viên có slotId NULL và gắn groupId. Ràng buộc:
 * event_key UNIQUE — FE gửi lại lô chơi / lượt nộp không sinh thông báo trùng.
 */
@Entity
@Table(name = "notification")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account nhận thông báo. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_account_id", nullable = false)
    private Account recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private NotificationType type;

    /** Trẻ liên quan. NULL với dòng gộp của giáo viên (một dòng cho cả lớp). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "slot_id")
    private LearnerSlot slot;

    /** Nhóm chứa sự kiện — luôn có, kể cả dòng của phụ huynh. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private LearnerGroup group;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "quiz_id")
    private UUID quizId;

    /** Gợi ý điều hướng cho FE: OPEN_LEARNER_REPORT / OPEN_QUIZ_RESULTS / OPEN_LEARNER_LIST. */
    @Column(name = "action_type", length = 32)
    private String actionType;

    @Column(name = "action_resource_id")
    private UUID actionResourceId;

    /** Khoá chống trùng do BE sinh; UNIQUE. */
    @Column(name = "event_key", length = 160, unique = true)
    private String eventKey;

    /** NULL = chưa đọc. Dòng gộp có em nộp thêm thì bị đặt lại NULL (nổi lại là chưa đọc). */
    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
