package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một huy hiệu hoặc chứng chỉ người học kiếm được. Người lớn quản lý đọc được; còn lịch sử chơi
 * chi tiết thì vẫn riêng tư.
 */
@Entity
@Table(name = "achievement")
@Getter
@Setter
@NoArgsConstructor
public class Achievement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "certificate_url", length = 255)
    private String certificateUrl;

    @Column(name = "earned_at")
    private Instant earnedAt;
}
