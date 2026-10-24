package com.doan.game.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    public OpenAPI api(@Value("${server.servlet.context-path:}") String contextPath) {
        return new OpenAPI()
                // Server TƯƠNG ĐỐI (24/10): để springdoc tự dựng thì nó lấy scheme của request nginx → BE (http),
                // ra "http://kidzeconomy.com.vn/finteen" → trang Swagger https bấm Execute bị chặn mixed content.
                // Đường dẫn tương đối thì trình duyệt tự ghép với scheme + host đang mở.
                .servers(List.of(new Server().url(contextPath.isEmpty() ? "/" : contextPath)))
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
