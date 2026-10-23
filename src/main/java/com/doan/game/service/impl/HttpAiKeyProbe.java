package com.doan.game.service.impl;

import com.doan.game.enums.AiProviderKind;
import com.doan.game.service.AiKeyProbe;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Gemini: GET /v1beta/models (liệt kê model, không tốn token), khoá đi trong header x-goog-api-key — không đặt trên URL để
 * khỏi lọt vào log. OpenRouter: GET /api/v1/key (Bearer) trả hạn mức và số dư của chính khoá đó (docs OpenRouter "Limits").
 */
@Component
public class HttpAiKeyProbe implements AiKeyProbe {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public Result probe(AiProviderKind provider, String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(15)).GET();
        switch (provider) {
            case GEMINI -> b.uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1"))
                    .header("x-goog-api-key", key);
            case OPENROUTER -> b.uri(URI.create("https://openrouter.ai/api/v1/key"))
                    .header("Authorization", "Bearer " + key);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int s = r.statusCode();
            if (s == 200) {
                return new Result(Outcome.OK, "200");
            }
            Outcome o = s == 400 || s == 401 || s == 403 ? Outcome.INVALID : s == 402 || s == 429 ? Outcome.QUOTA : Outcome.ERROR;
            return new Result(o, "HTTP " + s);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(Outcome.ERROR, "bị ngắt");
        } catch (Exception e) {
            return new Result(Outcome.ERROR, e.getClass().getSimpleName());
        }
    }
}
