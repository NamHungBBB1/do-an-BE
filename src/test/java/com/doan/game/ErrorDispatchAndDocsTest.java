package com.doan.game;

import com.doan.game.repository.AccountRepository;
import com.doan.game.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Hai lỗi chỉ lộ ra trên Tomcat THẬT (24/10), nên chạy cổng ngẫu nhiên chứ không MockMvc: MockMvc không chuyển
 * ngoại lệ tới /error, và không dựng URL server như sau nginx.
 *
 *  1. DB chết lúc decoder tra tài khoản → phải 5xx. Trước đây /error bị Security chặn → 401/3003 → FE đăng xuất.
 *  2. /v3/api-docs khai server tương đối, không phải "http://..." (Swagger trên https bị chặn mixed content).
 *
 * AccountRepository là mock ném lỗi DB: cả context này không cần tài khoản thật nào.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorDispatchAndDocsTest {

    @LocalServerPort int port;
    @Autowired TokenService tokenService;
    @MockitoBean AccountRepository accountRepo;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void databaseFailureWhileDecodingTokenIs5xxNot401() throws Exception {
        when(accountRepo.findById(any())).thenThrow(new DataAccessResourceFailureException("DB chết (giả lập)"));
        String token = tokenService.issueForAccount(UUID.randomUUID(), Set.of(), 0).accessToken();

        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).as(res.body()).isBetween(500, 599);
    }

    @Test
    void apiDocsDeclareRelativeServer() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        JsonNode servers = new ObjectMapper().readTree(res.body()).path("servers");
        assertThat(servers.size()).isEqualTo(1);
        assertThat(servers.get(0).path("url").asText()).isEqualTo("/").doesNotStartWith("http://");
    }
}
