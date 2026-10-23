package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một khoá API của nhà cung cấp AI cho hàng đợi AI nhiều tầng. Lưu mã hoá, chỉ hiện 4 ký tự cuối;
 * khoá bị dừng thì bỏ qua; khoá hết lượt thì nghỉ một lúc rồi mới dùng lại. Ràng buộc: Khoá chỉ
 * lưu dạng mã hoá (AES-GCM); API không bao giờ trả khoá, chỉ 4 ký tự cuối
 */
@Entity
@Table(name = "ai_key")
@Getter
@Setter
@NoArgsConstructor
public class AiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private AiProviderKind provider;

    @Column(name = "alias", length = 40, unique = true)
    private String alias;

    @Column(name = "secret_enc", columnDefinition = "text")
    private String secretEnc;

    @Column(name = "last4", length = 4)
    private String last4;

    @Column(name = "enabled", nullable = false, columnDefinition = "boolean default false not null")
    private boolean enabled;

    @Column(name = "priority")
    private Integer priority;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    @Column(name = "last_test_ok", nullable = false, columnDefinition = "boolean default false not null")
    private boolean lastTestOk;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "cool_down_until")
    private Instant coolDownUntil;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "created_by_id", nullable = true)
    private Account createdBy;

    @Column(name = "created_at")
    private Instant createdAt;
}
