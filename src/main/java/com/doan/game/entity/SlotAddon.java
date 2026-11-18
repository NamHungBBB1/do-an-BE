package com.doan.game.entity;

import com.doan.game.enums.PlanKind;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Slot mua thêm (add-on) của một tài khoản — 18/11/2026: gói và add-on đều VĨNH VIỄN, nên bảng này
 * chỉ ghi phần được cộng thêm, không có hạn dùng.
 *
 * Ví slot = base của gói + tổng {@code slots} của mọi dòng. "Đã dùng" KHÔNG trừ đi khi trả / xoá
 * slot: slot đã mở là tiêu hao vĩnh viễn (Q6).
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

    /** Loại gói được cộng thêm — PARENT cho ví gia đình, TEACHER cho ví lớp. */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private PlanKind kind;

    /** Số slot cộng thêm của đơn (packs × 2 với Parent, packs × 20 với Teacher). */
    @Column(name = "slots", nullable = false)
    private Integer slots;

    /** Transaction. Một giao dịch chỉ cấp một add-on (chống webhook trùng cấp hai lần). */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "transaction_id", nullable = true, unique = true)
    private Transaction transaction;

    @Column(name = "created_at")
    private Instant createdAt;
}
