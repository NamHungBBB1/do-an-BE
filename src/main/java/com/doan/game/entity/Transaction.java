package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một lần thanh toán PayOS: mua mới hoặc gia hạn gói Phụ huynh / Giáo viên. orderCode là số do
 * mình sinh, PayOS bắt buộc (02/10, theo tài liệu SDK); gatewayRef là paymentLinkId PayOS trả về.
 * Trạng thái chỉ chuyển tiến; webhook trùng bỏ qua. Bỏ UPGRADE. QR + tài khoản nhận + hạn lưu lại
 * (07/10, Hưng chốt hiện QR trong app) để bấm Mua lại khi còn đơn chờ cùng gói thì trả đúng đơn
 * đó, không tạo đơn mới. 09/10 (rà soát): months chép số tháng lúc mua (đổi giá / tháng sau không
 * ảnh hưởng); needsReview + note ghi lại tiền về mà không cấp gói (lệch số, về sau khi đơn hết
 * hạn) để admin đối soát thay vì chỉ nằm trong log.
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

    @Column(name = "order_code", unique = true)
    private Long orderCode;

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

    @Column(name = "qr_code", length = 512)
    private String qrCode;

    @Column(name = "bank_bin", length = 16)
    private String bankBin;

    @Column(name = "bank_account_number", length = 32)
    private String bankAccountNumber;

    @Column(name = "bank_account_name", length = 128)
    private String bankAccountName;

    @Column(name = "transfer_note", length = 64)
    private String transferNote;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "months")
    private Integer months;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "needs_review", nullable = false, columnDefinition = "boolean default false not null")
    private boolean needsReview;
}
