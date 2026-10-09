package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một phiên bản game đã phát hành (SemVer theo quy tắc 05/10: PATCH / MINOR / MAJOR). Bất biến;
 * lùi bản = đổi con trỏ isCurrent, không xoá gì. Trẻ đang chơi dở giữ phiên bản tới hết chương,
 * sang chương sau mới lấy bản hiện tại. Ràng buộc: Đúng một dòng isCurrent = true (service giữ)
 */
@Entity
@Table(name = "game_release")
@Getter
@Setter
@NoArgsConstructor
public class GameRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version", length = 16, unique = true)
    private String version;

    @Column(name = "note", length = 255)
    private String note;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "released_by_id", nullable = true)
    private Account releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "is_current", nullable = false, columnDefinition = "boolean default false not null")
    private boolean isCurrent;
}
