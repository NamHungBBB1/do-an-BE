package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Giá một pack slot của mỗi loại gói, admin đặt trên web (như Plan). Chưa có dòng thì chưa bán
 * pack đó (4002). Số slot mỗi pack là hằng số nghiệp vụ (2 / 20), không phải cột.
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
