package com.doan.game.entity;

import com.doan.game.enums.*;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phiếu ghi một tệp ảnh / giọng đọc nằm ở kho file ngoài (Cloudflare Pages / CDN). Tên theo hash
 * nội dung, không bao giờ ghi đè (đổi ảnh = tệp mới); DB chỉ giữ phiếu, không giữ byte.
 */
@Entity
@Table(name = "asset")
@Getter
@Setter
@NoArgsConstructor
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind")
    private AssetKind kind;

    @Column(name = "sha256", length = 64, unique = true)
    private String sha256;

    @Column(name = "path", length = 255)
    private String path;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    /** Account. Khoá ngoại thật, không phải một chuỗi id rời. */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "uploaded_by_id", nullable = true)
    private Account uploadedBy;

    @Column(name = "created_at")
    private Instant createdAt;
}
