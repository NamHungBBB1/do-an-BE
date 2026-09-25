package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một đường vào tài khoản; mật khẩu chỉ là một hàng trong số đó. Khoá trên (provider, subject) chứ
 * không trên email — OpenID Connect Core mục 5.7 nói rõ email KHÔNG được dùng làm định danh duy
 * nhất, vì nó đổi được và có thể cấp lại cho người khác.
 */
@Entity
@Table(name = "credential")
@Getter
@Setter
@NoArgsConstructor
public class Credential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider")
    private AuthProvider provider;

    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "password_hash", length = 72)
    private String passwordHash;

    @Column(name = "email_at_provider", length = 160)
    private String emailAtProvider;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at")
    private Instant createdAt;
}
