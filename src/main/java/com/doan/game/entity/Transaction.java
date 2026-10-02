package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần thanh toán PayOS: mua mới hoặc gia hạn gói Phụ huynh / Giáo viên. Trạng thái chỉ chuyển
 * tiến; webhook trùng bỏ qua. Bỏ UPGRADE.
 */
@Entity
@Table(name = "transaction")
@Getter
@Setter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "gateway_ref", length = 64, unique = true)
    private String gatewayRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private PlanKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose")
    private TransactionPurpose purpose;

    @Column(name = "amount")
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TransactionStatus status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;
}
