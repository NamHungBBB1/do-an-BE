package com.doan.game.service.impl;

import com.doan.game.enums.AiProviderKind;
import com.doan.game.service.AiTextClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gemini generateContent (trả JSON — responseMimeType application/json); khoá đi trong header x-goog-api-key, không đặt trên URL.
 * OpenRouter chat/completions. Model đổi bằng biến môi trường, không sửa code (tên model của nhà cung cấp đổi theo thời gian).
 */
@Component
public class HttpAiTextClient implements AiTextClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String geminiModel;
    private final String openRouterModel;

    public HttpAiTextClient(ObjectMapper json,
                            @Value("${app.ai.gemini-model:gemini-2.5-flash}") String geminiModel,
                            @Value("${app.ai.openrouter-model:google/gemini-2.5-flash}") String openRouterModel) {
        this.json = json;
        this.geminiModel = geminiModel;
        this.openRouterModel = openRouterModel;
    }

    @Override
    public String model(AiProviderKind provider) {
        return provider == AiProviderKind.GEMINI ? geminiModel : openRouterModel;
    }

    @Override
    public String generateJson(AiProviderKind provider, String key, String prompt) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json");
            ObjectNode body = json.createObjectNode();
            if (provider == AiProviderKind.GEMINI) {
                body.putArray("contents").addObject().putArray("parts").addObject().put("text", prompt);
                body.putObject("generationConfig").put("responseMimeType", "application/json").put("temperature", 0.2);
                b.uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent"))
                        .header("x-goog-api-key", key);
            } else {
                body.put("model", openRouterModel).put("temperature", 0.2);
                body.putArray("messages").addObject().put("role", "user").put("content", prompt);
                body.putObject("response_format").put("type", "json_object");
                b.uri(URI.create("https://openrouter.ai/api/v1/chat/completions")).header("Authorization", "Bearer " + key);
            }
            HttpResponse<String> r = http.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new AiCallException("HTTP " + r.statusCode(), r.statusCode() == 402 || r.statusCode() == 429);
            }
            JsonNode res = json.readTree(r.body());
            JsonNode text = provider == AiProviderKind.GEMINI
                    ? res.path("candidates").path(0).path("content").path("parts").path(0).path("text")
                    : res.path("choices").path(0).path("message").path("content");
            if (!text.isTextual()) {
                throw new AiCallException("AI không trả nội dung", false);
            }
            return text.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException("bị ngắt", false);
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            throw new AiCallException(e.getClass().getSimpleName(), false);
        }
    }
}
