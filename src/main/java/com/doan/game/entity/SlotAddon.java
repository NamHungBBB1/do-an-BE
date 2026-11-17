package com.doan.game.entity;

import com.doan.game.enums.PlanKind;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Slot mua thêm (add-on) của một tài khoản, chốt 17/11/2026: Parent +2 slot/pack, Teacher +20 slot/pack,
 * hết hạn CÙNG kỳ gói chính (không cộng dồn sang kỳ sau).
 *
 * Ví slot = base của gói + tổng slots của các dòng còn hạn (expiresOn >= hôm nay). Trần: Parent 10 slot,
 * Teacher 200 slot/kỳ — kiểm ở luồng mua, không kiểm ở đây.
 *
 * "Đã dùng" KHÔNG trừ đi khi trả / xoá slot: slot đã mở là tiêu hao vĩnh viễn trong kỳ (Q6). Vì vậy
 * bảng này chỉ ghi phần được cộng thêm, không có cột "đã dùng".
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

    /** Số slot cộng thêm (một pack Parent = 2, một pack Teacher = 20). */
    @Column(name = "slots", nullable = false)
    private Integer slots;

    /** Hạn dùng — luôn <= hạn của gói chính đang có (chốt Q5). */
    @Column(name = "expires_on", nullable = false)
    private LocalDate expiresOn;

    /** Transaction. Một giao dịch chỉ cấp một add-on (chống webhook trùng cấp hai lần). */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "transaction_id", nullable = true, unique = true)
    private Transaction transaction;

    @Column(name = "created_at")
    private Instant createdAt;
}
