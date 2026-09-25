package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần đổi tiền trong game lấy một món đồ.
 */
@Entity
@Table(name = "redemption")
@Getter
@Setter
@NoArgsConstructor
public class Redemption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    /** RewardItem. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private RewardItem item;

    @Column(name = "cost")
    private Integer cost;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;
}
