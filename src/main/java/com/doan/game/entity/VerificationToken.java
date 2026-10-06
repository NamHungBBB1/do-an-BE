package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Mã OTP 6 số xác minh email và đặt lại mật khẩu (chốt 14/09, làm 06/10): chỉ lưu hash BCrypt,
 * dùng một lần, hết hạn 10 phút, sai 5 lần (attempts) là hỏng.
 */
@Entity
@Table(name = "verification_token")
@Getter
@Setter
@NoArgsConstructor
public class VerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose")
    private TokenPurpose purpose;

    @Column(name = "token_hash", length = 64, unique = true)
    private String tokenHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "attempts")
    private Integer attempts;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at")
    private Instant createdAt;
}
