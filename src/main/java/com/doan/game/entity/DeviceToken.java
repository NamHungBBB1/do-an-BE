package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Token thông báo đẩy của một thiết bị đang đăng nhập tài khoản người lớn (app mobile, FCM). Mỗi
 * máy một dòng; xoá khi FCM báo token hết hiệu lực hoặc khi xoá tài khoản. Ràng buộc: token
 * UNIQUE: đăng ký lại cùng máy thì cập nhật dòng cũ (đổi chủ / lastSeenAt), không tạo dòng mới ·
 * INDEX(accountId): lấy mọi máy của một tài khoản khi gửi thông báo
 */
@Entity
@Table(name = "device_token", indexes = {
    @Index(columnList = "account_id")
})
@Getter
@Setter
@NoArgsConstructor
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "token", length = 512, unique = true)
    private String token;

    @Column(name = "platform", length = 16)
    private String platform;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
