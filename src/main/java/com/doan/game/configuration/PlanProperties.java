package com.doan.game.configuration;

import com.doan.game.enums.PlanKind;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Giá và thời hạn hai gói, đọc từ app.plan.* (biến môi trường PLAN_*). Giá bán CHƯA CHỐT: mặc định
 * trong application.properties chỉ là số tiền nhỏ để thử thanh toán thật.
 */
@ConfigurationProperties(prefix = "app.plan")
public record PlanProperties(long priceParent, long priceTeacher, int months) {

    public long giaCua(PlanKind kind) {
        return kind == PlanKind.PARENT ? priceParent : priceTeacher;
    }
}
