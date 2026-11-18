package com.doan.game.entity;

import com.doan.game.enums.PlanKind;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Giá một pack slot thêm, admin đặt trên web (18/11/2026) — cùng luật với {@link Plan}: không để
 * giá trong cấu hình máy chủ, chưa có dòng thì chưa bán được pack đó (4002).
 *
 * Số slot mỗi pack là hằng số nghiệp vụ (Parent 2, Teacher 20 — xem PlanServiceImpl), không phải
 * cột: đổi chia pack là đổi giá trị code có kiểm soát, không phải sửa dữ liệu giá.
 */
@Entity
@Table(name = "slot_addon_price")
@Getter
@Setter
@NoArgsConstructor
public class SlotAddonPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", unique = true)
    private PlanKind kind;

    @Column(name = "price")
    private Long price;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "updated_by_id", nullable = true)
    private Account updatedBy;
}
