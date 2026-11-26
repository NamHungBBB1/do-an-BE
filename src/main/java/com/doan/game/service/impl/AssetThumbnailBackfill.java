package com.doan.game.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tạo bù hình thu nhỏ cho ảnh đã có trong kho (26/11). Luồng riêng: vài trăm lần gọi cwebp không được chặn khởi động / health
 * check của CI deploy, và không chiếm luồng lập lịch dùng chung (@Async ở repo này rơi vào pool 3 luồng của @Scheduled).
 * Tắt bằng app.assets.thumb-backfill=false.
 */
@Component
@ConditionalOnProperty(name = "app.assets.thumb-backfill", havingValue = "true", matchIfMissing = true)
public class AssetThumbnailBackfill {

    private final AssetServiceImpl assets;

    public AssetThumbnailBackfill(AssetServiceImpl assets) {
        this.assets = assets;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        Thread t = new Thread(assets::backfillThumbnails, "asset-thumb-backfill");
        t.setDaemon(true);
        t.start();
    }
}
