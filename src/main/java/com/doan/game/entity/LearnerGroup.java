package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một gia đình (4 slot) hoặc một lớp (40 slot). Mỗi tài khoản tối đa 1 nhóm đang mở cho mỗi
 * context: openContext = context khi đang mở, NULL khi đã đóng, nên UNIQUE(ownerId, openContext)
 * chạy được trên cả H2 lẫn PostgreSQL. consentConfirmedAt: giáo viên xác nhận đã có đồng ý của phụ
 * huynh học sinh. Kết thúc nhóm thì đông cứng báo cáo, trả hạn mức. Ràng buộc: UNIQUE(ownerId,
 * openContext)
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
}
