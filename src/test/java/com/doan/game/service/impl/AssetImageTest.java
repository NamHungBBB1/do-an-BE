package com.doan.game.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Đọc kích thước WebP từ header cho cả ba kiểu (Pillow sinh): VP8 có mất mát, VP8L không mất mát, VP8X có kênh trong suốt. */
public class AssetImageTest {

    public static final byte[] VP8 = Base64.getDecoder().decode(
            "UklGRjoAAABXRUJQVlA4IC4AAACQAQCdASoFAAMAAUAmJaACdLoAA5gA/vCbQ/4DdfFtMv/ucD/uyf/2yf+pAAAA");   // 5×3
    public static final byte[] VP8L = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvBIAAAAdQlCIXpf+BiOh/AAA="); // 5×3
    public static final byte[] VP8X = Base64.getDecoder().decode(
            "UklGRnwAAABXRUJQVlA4WAoAAAAQAAAAAwAABQAAQUxQSAwAAAABDzD/ERFiBCL6HwJWUDggSgAAAFACAJ0BKgQABgABQCYlsAJ0ugB+AH6q+"
                    + "iqEAAD+8ut//nMXfX+dX9f/s5Mqz4zP/0uD/6XB/6XB37ZtZ1Y+/Rh2ijemrtGGCAAA");                     // 4×6, alpha
    public static final byte[] PNG_ALPHA = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAGCAYAAADkOT91AAAAHElEQVR4nGNkYPjPgAyYQMR/Bka4KCNWFaQJAABCogQIM3ZvpgAAAABJRU5ErkJggg==");

    @Test
    void readsWebpSizeForAllThreeKinds() {
        assertThat(AssetServiceImpl.webpSize(VP8)).containsExactly(5, 3);
        assertThat(AssetServiceImpl.webpSize(VP8L)).containsExactly(5, 3);
        assertThat(AssetServiceImpl.webpSize(VP8X)).containsExactly(4, 6);
        assertThat(AssetServiceImpl.webpSize(PNG_ALPHA)).isNull();
        assertThat(AssetServiceImpl.imageWidth(PNG_ALPHA)).isEqualTo(4);   // PNG / JPEG: ImageIO đọc header
        assertThat(AssetServiceImpl.imageWidth(new byte[]{1, 2, 3})).isEqualTo(-1);
    }

    @Test
    void sniffsByMagicBytesNotByName() {
        assertThat(AssetServiceImpl.sniff(VP8)).isEqualTo("webp");
        assertThat(AssetServiceImpl.sniff(PNG_ALPHA)).isEqualTo("png");
        assertThat(AssetServiceImpl.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("jpeg");
        assertThat(AssetServiceImpl.sniff("<?php echo 1; ?>".getBytes())).isNull();
    }
}
