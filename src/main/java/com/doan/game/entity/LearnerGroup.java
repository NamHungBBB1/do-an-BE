package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một gia đình hoặc một lớp. Từ 18/11/2026 slot lấy từ VÍ của tài khoản, không chép 4/40 vào nhóm:
 * FAMILY slotLimit = NULL, mỗi tài khoản 1 gia đình đang mở (openContext = FAMILY); CLASS mở nhiều
 * lớp (openContext = NULL), slotLimit = sĩ số giáo viên đặt (≥ 5), giữ chỗ trừ vào ví.
 * consentConfirmedAt: giáo viên xác nhận đã có đồng ý của phụ huynh. Kết thúc nhóm thì đông cứng
 * báo cáo; chỗ giữ chưa dùng về ví, slot đã tạo vẫn tính. Ràng buộc: UNIQUE(ownerId, openContext)
 */
@Entity
@Table(name = "learner_group", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"owner_id", "open_context"})
})
@Getter
@Setter
@NoArgsConstructor
public class LearnerGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private Account owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "context")
    private LearningContext context;

    @Column(name = "name", length = 80)
    private String name;

    @Column(name = "slot_limit")
    private Integer slotLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "open_context")
    private LearningContext openContext;

    @Column(name = "consent_confirmed_at")
    private Instant consentConfirmedAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "post_test_enabled_at")
    private Instant postTestEnabledAt;
}
