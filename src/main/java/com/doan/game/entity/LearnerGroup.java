package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cái hộp giữa người lớn và các chỗ ngồi: một đợt học. Giữ hạn mức chép từ gói (4 hoặc 40) và ngày
 * mở, ngày đóng; mỗi tài khoản tối đa một nhóm đang mở. Tài khoản sống nhiều năm còn một đợt thì
 * kết thúc, nên treo chỗ ngồi thẳng vào tài khoản là trộn các đợt với nhau vĩnh viễn. Bỏ phân biệt
 * gia đình/lớp từ 27/09.
 */
@Entity
@Table(name = "learner_group")
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

    @Column(name = "name", length = 80)
    private String name;

    @Column(name = "slot_limit")
    private Integer slotLimit;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;
}
