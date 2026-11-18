package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Quyền dùng một gói. Từ 18/11/2026 gói mua là VĨNH VIỄN: expiresOn = 9999-12-31 (sentinel, mọi
 * câu expiresOn >= today chạy nguyên), mỗi loại chỉ mua một lần (4008). Admin cấp được gói không
 * cần thanh toán (source = ADMIN, ghi grantedById, có thể có hạn). BE kiểm bằng
 * EntitlementService.activePlans. Ràng buộc: CHECK: source = PAYMENT thì transactionId NOT NULL;
 * source = ADMIN thì grantedById NOT NULL và reason NOT NULL (Hưng chốt 06/10)
 */
@Entity
@Table(name = "entitlement")
@Getter
@Setter
@NoArgsConstructor
public class Entitlement {

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

    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "source")
    private EntitlementSource source;

    /** Transaction. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "transaction_id", nullable = true, unique = true)
    private Transaction transaction;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "granted_by_id", nullable = true)
    private Account grantedBy;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at")
    private Instant createdAt;
}
