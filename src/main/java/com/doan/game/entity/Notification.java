package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Thông báo học tập cho tài khoản người lớn: xong chương, nộp đề, báo cáo sẵn sàng. Không lưu tên
 * trẻ và câu chữ (ghép lúc đọc). Giáo viên nhận một dòng gộp mỗi lớp + chương / đề, có em nộp thêm
 * thì cập nhật. Ràng buộc: eventKey UNIQUE: gửi lại cùng sự kiện không tạo dòng trùng ·
 * INDEX(recipientId, readAt): đếm chưa đọc · INDEX(recipientId, updatedAt): danh sách mới nhất
 * trước
 */
@Entity
@Table(name = "notification", indexes = {
    @Index(columnList = "recipient_id, read_at"),
    @Index(columnList = "recipient_id, updated_at")
})
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Account recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private NotificationType type;

    /** LearnerSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "slot_id", nullable = true)
    private LearnerSlot slot;

    /** LearnerGroup. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "group_id", nullable = true)
    private LearnerGroup group;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "quiz_id")
    private UUID quizId;

    @Column(name = "action_type", length = 32)
    private String actionType;

    @Column(name = "action_resource_id")
    private UUID actionResourceId;

    @Column(name = "event_key", length = 160, unique = true)
    private String eventKey;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
