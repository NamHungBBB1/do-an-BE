package com.doan.game.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI api() {
        return new OpenAPI().info(new Info()
                .title("Đồ án — Game giáo dục tài chính · Backend")
                .version("0.0.1")
                .description("""
                        Backend MỎNG. Mô phỏng chạy hoàn toàn ở client (chốt D3).
                        Backend chỉ là sổ ghi sự kiện — game phải chơi trọn vẹn khi server chết.
                        Đừng đưa logic tính toán tài chính vào đây."""));
    }
}
