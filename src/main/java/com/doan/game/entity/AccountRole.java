package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Vai nội bộ do admin cấp. Hiện chỉ ADMIN; Studio sẽ thêm GAME_EDITOR, GAME_REVIEWER, GAME_MANAGER
 * mà không phải đổi Account. Mỗi lần cấp / thu ghi vào RoleGrantLog kèm lý do. Ràng buộc:
 * UNIQUE(accountId, role)
 */
@Entity
@Table(name = "account_role", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"account_id", "role"})
})
@Getter
@Setter
@NoArgsConstructor
public class AccountRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;

    @Column(name = "granted_at")
    private Instant grantedAt;
}
