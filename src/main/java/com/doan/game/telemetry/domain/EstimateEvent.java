package com.doan.game.telemetry.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Một lượt CAM KẾT của người chơi — đối tượng đo chính của cả nghiên cứu
 * (xem knowledge/05-do-luong.md).
 *
 * Ghi thô, KHÔNG tính điểm ở đây. Sai số nghiên cứu là log(guess) - log(truth) CÓ DẤU,
 * tính lúc phân tích. Điểm thưởng trong game là hàm khác, có sàn có trần — lấy điểm
 * thưởng làm biến nghiên cứu là méo toàn bộ phân tích.
 */
@Entity
@Table(name = "estimate_event", indexes = {
        @Index(name = "idx_ee_player", columnList = "player_key"),
        @Index(name = "idx_ee_seed", columnList = "seed")
})
public class EstimateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Khoá ẩn danh của người chơi. KHÔNG lưu tên, email, hay bất cứ gì nhận dạng được. */
    @Column(name = "player_key", nullable = false, length = 64)
    private String playerKey;

    /** Bắt buộc trên MỌI dòng log — cùng seed thì cùng chuỗi sự kiện, mới so sánh được. */
    @Column(nullable = false, length = 32)
    private String seed;

    @Column(name = "build_version", nullable = false, length = 32)
    private String buildVersion;

    /** 'vien-chuc' | 'nhan-cong' — mô tả thôi, không kết luận (19 em/vai). */
    @Column(length = 32)
    private String vai;

    @Column(name = "item_id", nullable = false, length = 64)
    private String itemId;

    /** Tiền là SỐ NGUYÊN ĐỒNG (chốt D1). Đừng đổi sang double. */
    @Column(nullable = false)
    private Long guess;

    @Column(nullable = false)
    private Long truth;

    @Column(nullable = false, length = 16)
    private String unit;

    /** 'chac-chan' | 'doan-mo' — chốt trước khi xem kết quả. */
    @Column(length = 16)
    private String confidence;

    @Column(name = "attempt_index", nullable = false)
    private Integer attemptIndex;

    @Column(name = "shown_at", nullable = false)
    private Instant shownAt;

    /** committedAt - shownAt = thời gian cân nhắc. Dùng để LỌC, không làm outcome. */
    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @PrePersist
    void onCreate() {
        receivedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getPlayerKey() { return playerKey; }
    public void setPlayerKey(String v) { this.playerKey = v; }
    public String getSeed() { return seed; }
    public void setSeed(String v) { this.seed = v; }
    public String getBuildVersion() { return buildVersion; }
    public void setBuildVersion(String v) { this.buildVersion = v; }
    public String getVai() { return vai; }
    public void setVai(String v) { this.vai = v; }
    public String getItemId() { return itemId; }
    public void setItemId(String v) { this.itemId = v; }
    public Long getGuess() { return guess; }
    public void setGuess(Long v) { this.guess = v; }
    public Long getTruth() { return truth; }
    public void setTruth(Long v) { this.truth = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String v) { this.confidence = v; }
    public Integer getAttemptIndex() { return attemptIndex; }
    public void setAttemptIndex(Integer v) { this.attemptIndex = v; }
    public Instant getShownAt() { return shownAt; }
    public void setShownAt(Instant v) { this.shownAt = v; }
    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant v) { this.committedAt = v; }
    public Instant getReceivedAt() { return receivedAt; }
}
