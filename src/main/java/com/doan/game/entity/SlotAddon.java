package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Slot mua thêm (pack) của một tài khoản, vĩnh viễn: phụ huynh +2, giáo viên +20 mỗi pack. Ví =
 * gói gốc (4 / 40) + tổng slots; đã tạo − không bao giờ hoàn. Ràng buộc: transactionId UNIQUE:
 * webhook gọi lại không cấp pack hai lần
 */
@Entity
@Table(name = "slot_addon")
@Getter
@Setter
@NoArgsConstructor
public class SlotAddon {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private PlanKind kind;

    @Column(name = "slots")
    private Integer slots;

    /** Transaction. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "transaction_id", nullable = true, unique = true)
    private Transaction transaction;

    @Column(name = "created_at")
    private Instant createdAt;
}
