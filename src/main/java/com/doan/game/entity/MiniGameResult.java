package com.doan.game.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một đáp án trong một mini-game, kèm chuyện nó đúng hay sai. Đây là dòng dữ liệu duy nhất trò
 * chơi sinh ra mà CÓ đáp án đúng, nên cũng là dòng duy nhất gánh được kết luận nghiên cứu. Đổi tên
 * từ EstimateEvent ngày 25/09: hình dạng cũ giữ guess và truth — cặp giá trị trò chơi không bao
 * giờ sinh ra. Cột concept (thêm 26/09) ghi khái niệm câu đó kiểm tra; một chương phủ nhiều khái
 * niệm.
 */
@Entity
@Table(name = "mini_game_result")
@Getter
@Setter
@NoArgsConstructor
public class MiniGameResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** ChildSlot. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false)
    private ChildSlot slot;

    @Column(name = "chapter")
    private Integer chapter;

    @Column(name = "attempt_index")
    private Integer attemptIndex;

    @Column(name = "game_id", length = 40)
    private String gameId;

    @Column(name = "item_id", length = 64)
    private String itemId;

    @Column(name = "concept", length = 40)
    private String concept;

    @Column(name = "chosen", length = 64)
    private String chosen;

    @Column(name = "correct")
    private boolean correct;

    @Column(name = "build_version", length = 32)
    private String buildVersion;

    @Column(name = "played_at")
    private Instant playedAt;

    @Column(name = "received_at")
    private Instant receivedAt;
}
