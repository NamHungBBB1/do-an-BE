package com.doan.game.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI api() {
        return new OpenAPI()
                // Có mục này thì nút "Authorize" trên Swagger mới hiện, dán token vào là
                // thử được hết. Không có thì team FE phải đi dựng Postman riêng.
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .info(new Info()
                .title("Đồ án — Game giáo dục tài chính · Backend")
                .version("0.0.1")
                .description("""
                        Backend MỎNG. Mô phỏng chạy hoàn toàn ở client (chốt D3).
                        Backend chỉ là sổ ghi sự kiện — game phải chơi trọn vẹn khi server chết.
                        Đừng đưa logic tính toán tài chính vào đây."""));
    }
}
