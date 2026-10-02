package com.doan.game.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.payos.PayOS;
import vn.payos.core.ClientOptions;

/**
 * Một client PayOS dùng chung cho cả ứng dụng, từ SDK chính chủ vn.payos:payos-java.
 *
 * Bean chỉ tồn tại khi có khoá. Ở máy dev không đặt PAYOS_* thì không có bean; PaymentServiceImpl
 * nhận {@code ObjectProvider<PayOS>} và báo lỗi rõ ràng thay vì gọi PayOS với khoá rỗng.
 *
 * Cách dùng (theo README của SDK):
 *   client.paymentRequests().create(CreatePaymentLinkRequest.builder()...build())  -> checkoutUrl
 *   client.webhooks().verify(body)                                                 -> WebhookData
 *   client.webhooks().confirm(url)                                                 -> đăng ký URL
 */
@Configuration
@EnableConfigurationProperties(PayOsProperties.class)
public class PayOsConfig {

    @Bean
    @ConditionalOnExpression("!'${app.payos.client-id:}'.isEmpty()")
    PayOS payOS(PayOsProperties p) {
        return new PayOS(ClientOptions.builder()
                .clientId(p.clientId())
                .apiKey(p.apiKey())
                .checksumKey(p.checksumKey())
                .build());
    }
}
