package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Giá bán (VND) và số tháng của mỗi gói, ADMIN ĐẶT TRÊN WEB (02/10, Hưng: không để giá trong cấu
 * hình máy chủ). Một dòng mỗi loại gói; chưa có dòng thì chưa bán được gói đó. Transaction.amount
 * chép giá tại lúc mua, đổi giá sau không ảnh hưởng giao dịch cũ.
 */
@Entity
@Table(name = "plan")
@Getter
@Setter
@NoArgsConstructor
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", unique = true)
    private PlanKind kind;

    @Column(name = "price")
    private Long price;

    @Column(name = "months")
    private Integer months;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "updated_by_id", nullable = true)
    private Account updatedBy;
}
