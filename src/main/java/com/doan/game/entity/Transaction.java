package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần thanh toán qua PayOS. gatewayRef là UNIQUE nên một webhook bị gửi hai lần không thể cấp
 * gói hai lần.
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
    @Column(name = "package")
    private PackageKind packageValue;

    @Column(name = "amount")
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private TransactionStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;
}
